package com.example.visualchat.global.exception;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.common.response.FieldValidationError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Locale;

/**
 * [ROLE] 컨트롤러 처리 중 발생한 예외를 HTTP 상태와 공통 오류 응답으로 변환
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    // [TOPIC] 도메인 처리 실패 응답
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException e) {

        ErrorCode errorCode = e.getErrorCode();

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, e.getMessage()));
    }

    // [TOPIC] 요청 본문·파라미터 검증 실패 응답
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e,
            Locale locale
    ) {
        // [CAUTION] 거절된 입력값은 응답에 포함하지 않음
        List<FieldValidationError> errors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldValidationError(
                        error.getField(),
                        error.getCode(),
                        messageSource.getMessage(error, locale)
                ))
                .toList();

        // [CAUTION] 비밀번호 등 거절된 입력값은 로그에 포함하지 않음
        log.warn(
                "입력값 검증 실패: {}",
                errors.stream()
                        .map(error -> error.field() + ":" + error.code())
                        .toList()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "입력값을 확인해 주세요.", errors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {

        log.warn("MethodArgumentTypeMismatchException: {}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "요청 파라미터의 형식이 올바르지 않습니다."));
    }

    // [TOPIC] DB 유일성 제약 위반 응답
    // [WHY] 가입 트랜잭션이 롤백된 뒤 DB 예외를 공통 응답으로 변환
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        ErrorCode errorCode = resolveIntegrityError(e);

        // [CAUTION] DB 예외 메시지에는 입력값이 포함될 수 있어 그대로 노출하지 않음
        if (errorCode == ErrorCode.SERVER_ERROR) {
            log.error("매핑되지 않은 데이터 무결성 오류가 발생했습니다.");
        }

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode));
    }

    // [TOPIC] 필수 헤더 누락과 JSON 본문 해석 실패 응답
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception exception) {
        // [CAUTION] 예외 메시지에 포함될 수 있는 요청 원문을 로그에 남기지 않음
        log.warn("요청 형식 검증 실패: errorType={}", exception.getClass().getSimpleName());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "필수 요청 헤더와 JSON 본문을 확인해 주세요."));
    }

    // [TOPIC] 예상하지 못한 서버 오류 응답
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {

        log.error("예상치 못한 오류 - ", e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SERVER_ERROR));
    }

    // [TOPIC] DB 제약 이름을 이용한 중복 원인 구분
    private ErrorCode resolveIntegrityError(Throwable error) {
        Throwable cause = error;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) {

                String constraintName = violation.getConstraintName();

                // [CAUTION] User 엔티티의 유일성 제약 이름과 함께 관리
                if ("uk_users_email".equals(constraintName)) {
                    return ErrorCode.DUPLICATE_EMAIL;
                }

                if ("uk_users_nickname".equals(constraintName)) {
                    return ErrorCode.DUPLICATE_NICKNAME;
                }
            }

            cause = cause.getCause();
        }

        return ErrorCode.SERVER_ERROR;
    }
}
