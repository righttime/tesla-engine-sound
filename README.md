# Tesla Engine Sound

테슬라 Model Y에 가상 엔진 사운드를 실시간 출력

## 구조

```
tesla-engine-sound/
├── engine-sim/          # engine-sim-android (submodule)
│   └── android/         # AAudio 엔진 사운드 엔진
├── app/                 # Tesla OBD2 BLE 앱
└── README.md
```

## 기능

- ELM327 BLE OBD2 어댑터 연결
- 실시간 모터 RPM / 가속 페달 데이터 수집
- 엔진 사운드 실시간 출력
- Tesla Model Y 전용 PID 매핑

## 하드웨어

- Tesla Model Y
- ELM327 BLE OBD2 어댑터
- Android 스마트폰
