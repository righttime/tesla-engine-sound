# Tesla Engine Sound - Project Plan

## 🎯 목표
Tesla Model Y에서 실시간 OBD2/CAN 데이터를 수신하여 가상 엔진 사운드를 출력하는 Android 앱

## 🏗️ 아키텍처

```
┌─────────────┐    BLE     ┌──────────────┐    JNI     ┌──────────────┐
│  ELM327     │◄──────────►│  Android App │──────────►│ Engine Audio │
│  OBD2       │            │              │           │ Engine (AAudio)│
│  Adapter    │            │ - BLE Service│           │ (submodule)  │
└─────────────┘            │ - PID Parser │           └──────────────┘
                           │ - RPM Mapper │
                           │ - UI (Compose)│
                           └──────────────┘
```

## 📡 Tesla Model Y CAN Packets (참조: amund7/CANBUS-Analyzer)

### 엔진 사운드에 필요한 핵심 PID

| CAN ID | 데이터 | 파싱 | 용도 |
|--------|--------|------|------|
| `0x257` | Speed (km/h) | bit 12, 12bit, 0.08, offset -40 | 차량 속도 |
| `0x118` | Accelerator Pedal (%) | bit 32, 8bit, 0.4, offset 0 | **Throttle** |
| `0x118` | Brake Pedal (On/Off) | bit 19, 2bit, 1, offset 0 | 브레이크 |
| `0x2E5` | Front Motor Power (kW) | bit 16, 11bit, signed, 0.5 | 전/후륜 구동 |
| `0x266` | Rear Motor Power (kW) | bit 16, 11bit, signed, 0.5 | 전/후륜 구동 |
| `0x1D4` | Front Torque (Nm) | bytes[5] + (bytes[6]&0x1F)<<8, * 0.25 | 모터 토크 |
| `0x154` | Rear Torque (Nm) | bytes[5] + (bytes[6]&0x1F)<<8, * 0.25 | 모터 토크 |
| `0x108` | Rear Motor RPM (commented) | bytes[5]+(bytes[6]<<8) | ⚠️ 주석처리됨 |
| `0x132` | Battery Voltage/Current | V: (b0+b1<<8)/100, A: 1000-Int16/10 | 배터리 상태 |

### 추가 정보 (UI 표시용)
| CAN ID | 데이터 | 용도 |
|--------|--------|------|
| `0x292` | SOC UI (%) | 배터리 잔량 |
| `0x352` | Nominal/Energy kWh | 배터리 용량 |
| `0x3B6` | Odometer (km) | 주행거리 |
| `0x129` | Steering Angle | 조향각 |
| `0x212` | Battery Temp | 배터리 온도 |
| `0x321` | Outside Temp | 외기온도 |
| `0x376` | Inverter Temps | 인버터 온도 |

## 🔄 RPM 매핑 전략

Tesla는 전기차라 엔진 RPM이 없음. **가상 RPM** 생성:

```
가상 RPM = f(Accelerator Pedal, Speed, Motor Power)

기본 공식:
- Idle: 800 RPM (정차 상태)
- 주행: Speed 기반 기본 RPM + Accelerator 보정
- RPM = 800 + (Speed / MaxSpeed * 6000) * (0.3 + 0.7 * Accelerator/100)

고급 (Power 기반):
- F/R Motor Power 합산 → 토크 추정 → RPM 매핑
- 가속 중: RPM 빠르게 상승
- 감속/회생: RPM 유지 후 천천히 하락 (엔진 브레이킹)
- 브레이크: RPM 급감 + 백파이어 유발
```

### 예상 RPM 곡선
```
Pedal 0%, Speed 0km/h → 800 RPM (idle)
Pedal 50%, Speed 60km/h → 2500 RPM
Pedal 100%, Speed 100km/h → 5500 RPM
Pedal 0%, Speed 100km/h → 2000 RPM (coasting)
Brake ON → RPM 급감 + backfire
```

## 📁 프로젝트 구조

```
tesla-engine-sound/
├── engine-sim/              # submodule: engine-sim-android
├── app/                     # Tesla OBD2 앱
│   ├── src/main/java/com/tesla/enginesound/
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── DashboardScreen.kt     # 메인 대시보드
│   │   │   ├── ConnectionScreen.kt    # BLE 연결
│   │   │   └── theme/
│   │   ├── ble/
│   │   │   ├── BleManager.kt          # BLE 연결 관리
│   │   │   ├── Elm327Protocol.kt      # ELM327 AT 커맨드
│   │   │   └── ObdParser.kt           # PID 응답 파싱
│   │   ├── tesla/
│   │   │   ├── TeslaCanParser.kt      # Tesla CAN ID 해석
│   │   │   └── RpmMapper.kt           # Tesla → Virtual RPM
│   │   └── audio/
│   │       └── EngineAudioBridge.kt   # engine-sim JNI 브릿지
│   └── src/main/cpp/
│       └── (engine-sim native lib)
├── docs/
│   └── PLAN.md              # 이 파일
└── README.md
```

## 🔧 기술 스택

- **Language:** Kotlin + C++ (JNI)
- **UI:** Jetpack Compose (Material 3, Dark theme)
- **Audio:** AAudio (engine-sim submodule)
- **BLE:** Android Bluetooth Low Energy API
- **Protocol:** ELM327 AT Commands
- **Build:** Gradle 8.5, NDK 25.1

## 📋 구현 Phase

### Phase 1: BLE + OBD2 연결
- [ ] BLE Service (ELM327 자동 감지, 연결, 재연결)
- [ ] ELM327 프로토콜 (AT Z, AT SP 6, AT MA 등)
- [ ] Tesla CAN 모니터링 모드 설정
- [ ] 연결 상태 UI

### Phase 2: 데이터 파싱 + RPM 매핑
- [ ] CAN ID 필터링 (필요한 ID만 수신)
- [ ] Tesla PID 파서 (0x118, 0x257, 0x2E5, 0x266 등)
- [ ] Virtual RPM 생성 알고리즘
- [ ] Throttle/Brake → 엔진 입력 매핑
- [ ] 스무딩 (급격한 RPM 변화 방지)

### Phase 3: 오디오 연동
- [ ] engine-sim submodule 통합
- [ ] Virtual RPM → nativeSetThrottle 전달
- [ ] 실시간 오디오 출력
- [ ] 레이턴시 최소화 (< 50ms target)

### Phase 4: UI/UX
- [ ] 대시보드: RPM 게이지, 속도, 페달 위치
- [ ] 엔진 프리셋 선택 (I4/V6/V8/V12)
- [ ] 캐빈 필터 on/off
- [ ] 연결 상태 표시
- [ ] OBD2 데이터 오버레이

### Phase 5: 고급 기능
- [ ] 주행 로깅 + 재생
- [ ] 커스텀 RPM 커브 편집
- [ ] 배기음 캐릭터 조절
- [ ] CarPlay/Android Auto 지원 (future)

## ⚠️ 알려진 제약

1. **ELM327 BLE 제한:** 표준 OBD2 PID는 Tesla에서 대부분 지원 안 함. CAN passthrough 모드 필요
2. **CAN ID 접근:** 일부 CAN ID는 Tesla에서 암호화/인증 필요할 수 있음
3. **Motor RPM:** Model 3에서 주석 처리됨 — Speed + Power로 추정 필요
4. **레이턴시:** BLE 통신 지연 + 오디오 파이프라인 지연 고려
5. **배터리 소모:** BLE + AAudio 지속 실행

## 🔑 핵심 참고

- [amund7/CANBUS-Analyzer](https://github.com/amund7/CANBUS-Analyzer) — Tesla Model 3 CAN 패킷 정의
- [engine-sim](https://github.com/ange-yaghi/engine-sim) — 오리지널 엔진 사운드 엔진
- ELM327 AT Command Set — BLE OBD2 통신 프로토콜
