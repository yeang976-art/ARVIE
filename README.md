# ARVIE · 아르비

**나만의 AI 레브와 대화를 이어가고, 대화 기록과 이용량을 관리하는 서비스입니다.**

ARVIE는 고유한 설정과 성격을 가진 **레브(REVE)**와 지속적으로 교류하는 경험을 지향합니다. 장기적으로 현실 공간과 연결되는 AR 경험을 계획하고 있으며, 현재는 회원가입·레브 배정·텍스트 대화·이용량 관리를 담당하는 백엔드를 개발하고 있습니다.

> 현재 저장소에는 서버 API가 구현되어 있습니다. 클라이언트와 AR 기능은 포함하지 않습니다. AI 연동 코드는 가짜 제공자를 사용한 통합 테스트를 완료했으며, 실제 OpenAI API를 통한 대화는 아직 검증하지 않았습니다.

## 서비스의 두 가지 개념

| 이름 | 의미 |
| --- | --- |
| **REVE · 레브** | 고유한 설정과 성격을 가진 AI 캐릭터입니다. 회원에게 배정된 레브와 전용 채팅방에서 대화합니다. |
| **VIE** | AI 이용량을 관리하는 단위입니다. 요청마다 고정 차감하지 않고 실제 AI 사용 비용에 비례해 계산합니다. |

## 이용 흐름

1. 회원가입 시 체험 대상 중 가중 랜덤 방식으로 레브 한 명을 배정받습니다.
2. 로그인하고 본인의 정보·배정 레브·잔여 VIE를 확인합니다.
3. 배정 레브의 채팅방을 엽니다. 같은 배정에는 기존 채팅방을 재사용합니다.
4. 레브 설정과 유효한 최근 대화를 바탕으로 메시지를 주고받습니다.
5. 성공한 답변을 저장하고 실제 사용량에 따라 VIE를 정산합니다.
6. 다시 접속해 같은 채팅방과 보관 중인 대화를 이어갑니다.

현재 MVP에서는 **회원당 체험 레브 한 명**, **배정 레브당 채팅방 한 개**를 제공합니다.

## 구현 현황

| 구분 | 내용 | 상태 |
| --- | --- | --- |
| 회원가입 | 비밀번호 해시 저장, 이메일·닉네임 중복 검사 | 구현 |
| 초기 배정 | 체험 레브 가중 랜덤 배정 및 초기 이용 주기 생성 | 구현 |
| 인증 | JWT 발급과 Bearer 토큰 기반 요청 인증 | 구현 |
| 본인 정보 | 회원 정보·배정 레브·VIE 이용량 조회 | 구현 |
| 채팅방 | 배정 레브의 단일 채팅방 열기·설정 조회 | 구현 |
| AI 대화 | 입력 토큰 계수, 대화 요청, 답변·사용량 변환 | 구현 / 실제 API 호출 미검증 |
| 요청 멱등성 | 동일 키 재전송 시 기존 요청과 결과 재사용 | 구현 |
| VIE 관리 | 계정별 30일 주기, 원자적 예약·성공 정산·실패 예약 해제 | 구현 |
| 대화 보관 | 메시지별 만료 시각 계산, 만료 원문 조회 차단 | 구현 |
| 기록 관리 | 메시지 목록 커서 조회, 보관 기간 변경, 원문 삭제·자동 정리 | 후속 개발 |
| 운영 복구 | 완료 저장 실패·장애 중단 요청의 자동 복구 | 후속 개발 |
| 확장 기능 | 구독·추첨 티켓·요약 기억·음성·AR 클라이언트 | 후속 계획 |

## VIE 이용 정책

- 계정에 **30일 주기마다 20,000 VIE**를 제공합니다.
- 주기는 가입 완료 시각을 기준으로 계산합니다.
- VIE는 캐릭터별이 아닌 **계정 전체 이용량**으로 관리합니다.
- 환산 기준은 **1 USD = 10,000 VIE**입니다.
- 입력·캐시 읽기·캐시 쓰기·출력의 실제 사용량을 구분해 계산합니다.
- 답변 생성 전에 필요한 상한을 예약하고, 성공하면 실제 사용량으로 정산합니다.
- 실패로 확정 처리한 요청은 최종 VIE를 차감하지 않고 예약량을 해제합니다.

**예약량은 최종 차감액이 아닙니다.** 실패 요청에서도 제공사 비용이 발생했다면 해당 비용은 운영 측 부담으로 남을 수 있습니다.

현재 대화 제한은 사용자 메시지 **2,000자**, 과거 맥락 **최근 메시지 20개**, 전체 입력 **16,384토큰**, 최대 출력 **1,024토큰**입니다.

## 주요 설계

### 회원가입의 원자성

회원 생성·최초 레브 배정·이용 주기 생성을 하나의 트랜잭션으로 처리합니다. 배정에 실패하면 회원 생성도 롤백합니다.

중복 사전 조회와 별도로 DB 유일성 제약을 적용해 동시 가입 요청에서도 이메일·닉네임 중복을 차단합니다.

### 재전송과 중복 생성 방지

메시지 전송 시 `Idempotency-Key`를 사용합니다.

- 같은 회원이 같은 키와 같은 내용을 재전송하면 기존 요청을 반환합니다.
- 같은 키에 다른 내용을 보내면 충돌로 거절합니다.
- 신규 접수에 성공한 요청만 답변 생성을 진행합니다.

동시 최초 전송에서는 입력 계수 호출이 겹칠 수 있지만, 신규 접수 여부를 기준으로 답변 생성을 구분합니다.

### 외부 호출과 저장 트랜잭션 분리

AI 응답을 기다리는 동안 DB 트랜잭션을 유지하지 않습니다.

```text
소유권·입력 확인
  → 대화 맥락 준비
  → 입력 토큰 계수
  → VIE 예약 및 요청 저장
  → 사용자 메시지 저장
  → 트랜잭션 밖에서 AI 호출
  → AI 답변·요청 완료·VIE 정산을 함께 커밋
```

답변 저장에 실패하면 해당 완료 트랜잭션의 요청 상태·제공사 응답 ID·정산 변경도 롤백합니다.

### 메시지 보관과 답변의 독립성

메시지마다 전송 시각과 채팅방 보관 기간으로 만료 시각을 계산합니다. 물리 삭제 전이라도 만료 시각에 도달한 원문은 대화 맥락과 답변 조회에서 제외합니다.

생성 중 참고 원문이 삭제되더라도 원문을 복원하지 않으며, 완성된 답변은 자신의 보관 기간에 따라 유지합니다.

## 기술 스택

| 영역 | 사용 기술 | 선택 이유 |
| --- | --- | --- |
| 언어·서버 | Java 21, Spring Boot 4.1.1 | 타입과 트랜잭션 경계를 중심으로 도메인 로직 구성 |
| 인증 | Spring Security, OAuth2 Resource Server, JWT | Bearer 토큰 검증과 인증 처리를 프레임워크에 위임 |
| 데이터 | Spring Data JPA, PostgreSQL 17 | 영속성 관리와 DB 제약을 함께 사용해 정합성 검증 |
| AI 연동 | OpenAI Responses API, Java HTTP Client | 도메인 인터페이스와 제공사 구현 분리 |
| 테스트 | JUnit Jupiter, Mockito, H2, PostgreSQL | 빠른 단위 검증과 실제 DB 동작 검증 병행 |
| 개발 환경 | Gradle Wrapper, Docker Compose | 빌드 버전과 로컬 DB 실행 환경 관리 |
| 품질 관리 | GitHub Actions, JaCoCo, SonarQube Cloud, CodeRabbit | 빌드·테스트·커버리지·코드 리뷰 자동화 |

## 프로젝트 구조

```text
ARVIE/
├── .github/workflows/           # 빌드·테스트·정적 분석
├── docs/                       # 상세 정책
└── visual-chat/
    ├── docker-compose.yaml
    ├── build.gradle
    └── src/
        ├── main/java/com/example/visualchat/
        │   ├── common/         # 도메인에서 사용하는 공통 응답·예외·기반 엔티티
        │   ├── domain/         # 회원·레브·채팅방·메시지·요청·이용 주기
        │   └── global/         # 보안·전역 설정·예외 처리·외부 AI 구현
        └── test/
```

Service는 해당 도메인의 저장소를 사용하고, 여러 도메인과 외부 요소의 조율은 Command에서 담당합니다.

## 로컬 실행

### 준비 환경

- JDK 21
- Docker 및 Docker Compose
- 기본 사용 포트: PostgreSQL `5432`, 애플리케이션 `8080`
- Gradle은 저장소의 Wrapper 사용

아래 명령은 **Windows PowerShell 기준**입니다.

### 1. 저장소 준비

```powershell
git clone https://github.com/yeang976-art/ARVIE.git
cd ARVIE/visual-chat
java -version
```

### 2. PostgreSQL 실행

```powershell
$env:POSTGRES_DB = "arvie"
$env:POSTGRES_USER = "arvie"
$env:POSTGRES_PASSWORD = [Net.NetworkCredential]::new(
    "",
    (Read-Host "로컬 DB 비밀번호" -AsSecureString)
).Password

docker compose up -d
docker compose ps
```

PostgreSQL이 `healthy` 상태가 된 뒤 진행합니다. 기존 볼륨을 사용한다면 처음 설정한 DB 계정과 비밀번호를 사용해야 합니다.

### 3. 서버 환경변수 설정

| 환경변수 | 설명 |
| --- | --- |
| `POSTGRES_DB` | DB 이름. 기본값 `arvie` |
| `POSTGRES_USER` | DB 사용자 |
| `POSTGRES_PASSWORD` | DB 비밀번호 |
| `JWT_SECRET` | Base64 디코딩 후 32바이트 이상인 JWT 서명 키 |
| `JWT_ACCESS_TOKEN_TTL` | 토큰 유효기간. 기본값 `30m` |
| `AUTH_DUMMY_PASSWORD_HASH` | 로그인 응답 시간 차이를 줄이는 더미 BCrypt 해시 |
| `OPENAI_API_KEY` | 실제 AI 호출 시 필요. 비어 있어도 서버 기동 가능 |

로컬 개발용 JWT 키를 현재 터미널에 생성합니다.

```powershell
$arvieKeyBytes = New-Object byte[] 32
$arvieRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
$arvieRandom.GetBytes($arvieKeyBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($arvieKeyBytes)
$arvieRandom.Dispose()
```

다음은 로컬 PostgreSQL의 `pgcrypto`로 강도 10의 더미 BCrypt 해시를 생성하는 예시입니다. 실제 회원 비밀번호와 무관한 값입니다.

```powershell
docker compose exec -T postgres psql `
  -U $env:POSTGRES_USER -d $env:POSTGRES_DB `
  -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"

$env:AUTH_DUMMY_PASSWORD_HASH = (
    docker compose exec -T postgres psql `
      -U $env:POSTGRES_USER -d $env:POSTGRES_DB `
      -At -c "SELECT crypt(gen_random_uuid()::text, gen_salt('bf', 10));"
).Trim()
```

환경변수는 현재 터미널과 그 터미널에서 실행한 서버에 적용됩니다. IntelliJ에서 서버를 실행한다면 실행 구성에 별도로 주입해야 합니다.

> Docker Compose의 `.env` 파일은 Spring Boot에 자동으로 전달되지 않습니다. 실제 키·비밀번호·토큰은 저장소에 커밋하지 않습니다.

### 4. 서버 시작

```powershell
.\gradlew.bat bootRun
```

기본 주소는 `http://localhost:8080`입니다. 현재 `ddl-auto: update`를 사용하는 로컬 개발 설정이며, 운영 스키마 마이그레이션은 후속 작업입니다.

### 5. 로컬 체험 레브 등록

초기 레브 데이터가 없다면 회원가입 시 배정할 대상이 없어 가입이 롤백됩니다.

서버 최초 기동으로 테이블이 생성된 뒤, 다른 터미널에서 `visual-chat` 디렉터리로 이동해 아래 예시를 실행합니다. DB 사용자·이름은 위 설정 기준입니다.

```powershell
@'
INSERT INTO reves (
    code, name, mbti, description, system_prompt,
    trial_enabled, trial_weight, created_at, updated_at
)
VALUES (
    'LOCAL_DEMO_REVE',
    '루미',
    'INFP',
    '로컬 실행을 확인하기 위한 체험 레브입니다.',
    '당신의 이름은 루미입니다. 사용자에게 한국어로 친근하게 답변하세요.',
    true, 1.000000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO NOTHING;
'@ | docker compose exec -T postgres psql `
    -U arvie -d arvie -v ON_ERROR_STOP=1
```

이 데이터는 실행 확인용 한 명의 예시이며, 실제 체험 레브 전체 목록을 의미하지 않습니다.

## 주요 API

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 회원가입·최초 레브 배정 |
| POST | `/api/auth/login` | 로그인·Access Token 발급 |
| GET | `/api/users/me` | 내 정보 조회 |
| GET | `/api/users/me/reves` | 내 배정 레브 조회 |
| GET | `/api/users/me/usage` | 현재 VIE 이용량 조회 |
| POST | `/api/reves/{reveId}/chat-rooms` | 채팅방 열기 |
| GET | `/api/chat-rooms/{roomId}/settings` | 채팅방 설정 조회 |
| POST | `/api/chat-rooms/{roomId}/messages` | 메시지 전송 |
| GET | `/api/ai-requests/{requestId}` | 요청 상태·유효한 답변 조회 |

회원가입·로그인을 제외한 API에는 다음 헤더가 필요합니다.

```http
Authorization: Bearer <AccessToken>
```

로그인 요청은 이메일을 `identifier` 필드로 전달합니다.

```json
{
  "identifier": "demo@example.com",
  "password": "<가입한 비밀번호>"
}
```

메시지 전송 예시:

```http
POST /api/chat-rooms/1/messages
Authorization: Bearer <AccessToken>
Idempotency-Key: chat-send-001
Content-Type: application/json

{
  "content": "안녕! 오늘은 뭐 하고 있었어?"
}
```

같은 메시지 전송을 재시도할 때는 같은 멱등키를 사용하고, 새로운 메시지를 보낼 때는 새로운 키를 사용합니다.

## 테스트

`visual-chat` 디렉터리에서 실행합니다.

```powershell
# 전체 테스트
.\gradlew.bat test

# 특정 테스트
.\gradlew.bat test --tests "*MessageSendIntegrationTest"

# 빌드 및 테스트
.\gradlew.bat build
```

macOS·Linux에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용합니다.

일반 테스트는 H2와 테스트 대역을 사용합니다. PostgreSQL 테스트는 별도의 테스트 DB 설정이 있을 때 실행하며, 실제 OpenAI API를 호출하지 않습니다.

PostgreSQL 검증용 환경변수:

```text
ARVIE_POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:5433/arvie_test
ARVIE_POSTGRES_TEST_USER=<테스트 DB 사용자>
ARVIE_POSTGRES_TEST_PASSWORD=<테스트 DB 비밀번호>
```

테스트 DB는 스키마 생성·삭제와 데이터 정리가 수행되는 전용 환경이어야 합니다. 테스트 코드가 허용하는 DB 이름은 `arvie_test` 또는 `arvie_signup_test`입니다.

**2026-11-12 로컬 검증 결과**

- 전체 테스트 **880개 통과**
- PostgreSQL 테스트 **90개 통과**
- 실패·오류·건너뛴 테스트 **0개**
- 빌드 성공

주요 검증 범위는 정상 대화, 멱등 재전송, 동시 최초 전송의 생성 1회, 실패 무차감, 답변 INSERT 실패 시 전체 롤백, 만료·삭제 원문 차단입니다.

보고서 위치:

- 테스트: `visual-chat/build/reports/tests/test/index.html`
- 커버리지: `visual-chat/build/reports/jacoco/test/html/index.html`

## 현재 한계와 다음 작업

- 실제 OpenAI API 키를 연결한 호출 검증과 클라이언트 연동이 남아 있습니다.
- 현재 타임아웃은 서비스 요청을 실패로 종료하고 무차감 처리합니다. 제공사에서 생성이 끝났는지는 별도로 확인되지 않을 수 있습니다.
- 완료 저장 결과가 불명확하면 자동 재생성하지 않습니다. `PROCESSING` 상태와 예약량이 남을 수 있으며, 자동 복구는 아직 구현하지 않았습니다.
- 서로 다른 멱등키의 동시 전송은 허용하며, 각각 준비 시점의 대화 맥락을 사용합니다.
- 메시지 목록 조회·보관 기간 변경·자동 물리 삭제·재시작 정리를 이어서 구현할 예정입니다.
- 현재 레브 배정 데이터와 로컬 실행 예시는 최종 서비스 콘텐츠가 아닙니다.

## 관련 문서

- [서비스 기획](https://app.notion.com/p/406b4dedb89480f69b30c5a0f6e8328f)
- [첫 MVP 범위·시나리오](https://app.notion.com/p/407b4dedb894807eac80d4d597025b50)
- [SA — 첫 MVP 개발 계획](https://app.notion.com/p/407b4dedb89480f5ad33e4d4cf5ceae3)
- [API 명세서 — 설계 초안](https://app.notion.com/p/409b4dedb89480109ccfc55a4ee0ac9c)
- [VIE 환산·예약·정산 정책](docs/vie-metering.md)

Notion 문서는 접근 권한이 필요할 수 있습니다. 설계 문서에는 미구현 계획이 포함되어 있으며, 상세 VIE 문서의 결과 불명확 처리 원칙과 현재 타임아웃 처리에는 차이가 있습니다. 현재 제공 범위와 동작은 이 README의 구현 현황·API 목록·현재 한계를 기준으로 확인할 수 있습니다.