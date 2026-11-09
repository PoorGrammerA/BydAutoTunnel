# AutoTunnel 커스텀 및 포크 가이드 (Fork & Customization)

AutoTunnel 프로젝트에 관심을 가져주셔서 감사합니다!

---

## 📢 유지보수 상태 및 안내 (Maintenance Notice)

- **본 프로젝트는 개인 차량 주행 편의를 위해 개발된 오픈소스 프로젝트입니다.**
- **메인테이너의 개인 사정 및 일정상 빈번한 유지보수, 이슈 대응, Pull Request(PR) 검토 및 머지가 어렵습니다.**
- 기능 추가, 오류 수정, 다른 차종/AOSP 시스템 연동, 특정 지역 터널 DB 보완 등이 필요하신 경우 **저장소를 직접 Fork(포크)하여 각자의 환경에 맞게 수정하여 사용하시는 것을 강력히 권장합니다.**
- 본 프로젝트는 **Apache License 2.0** 라이선스 하에 배포되므로, 누구나 자유롭게 Fork하여 코드를 수정하고, 자신만의 빌드를 생성하여 사용하실 수 있습니다.

---

## 🍴 Fork 및 나만의 빌드 생성 방법 (How to Fork & Build)

### 1. 저장소 Fork
1. GitHub 우측 상단의 **Fork** 버튼을 눌러 본인의 GitHub 계정으로 저장소를 복제합니다.
2. 로컬 컴퓨터에 복제합니다:
   ```bash
   git clone https://github.com/PoorGrammerA/BydAutoTunnel.git
   cd BydAutoTunnel
   ```

### 2. 코드 수정 및 커스터마이징
- **차종별 하드웨어 브릿지 수정**:
  - `app/src/main/java/com/poorgrammera/autotunnel/vehicle/VehicleController.java`
  - 타 차종 AOSP나 다른 BCM 인터페이스에 맞춰 리플렉션 호출 메서드를 수정할 수 있습니다.
- **터널 감지 알고리즘 및 민감도 튜닝**:
  - `app/src/main/java/com/poorgrammera/autotunnel/detector/TunnelDetector.java`
  - 접근 감지 거리(기본 200m), 방위각 일치 오차각(기본 70°), 출구 쿨다운 시간(기본 5초) 등을 조정할 수 있습니다.
- **알림 사운드 교체**:
  - `app/src/main/res/raw/tunnel_start.opus`
  - `app/src/main/res/raw/tunnel_end.opus`
  - 자신이 원하는 오디오 파일(Opus/MP3/OGG 등)로 교체하여 개성 있는 사운드로 변경 가능합니다.

### 3. 빌드 및 APK 생성
```bash
# 단위 테스트 검증
./gradlew testDebugUnitTest

# APK 빌드
./gradlew assembleDebug
```
빌드된 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성되며, adb 명령어나 USB 드라이브를 통해 차량 헤드유닛에 직접 설치할 수 있습니다.

---

## 🗺️ 터널 데이터베이스 커스텀

- **앱 내 시각적 터널 편집기 활용**:
  - 메인 화면 우측 하단의 `[터널 DB 편집기]`를 통해 새로운 터널을 지도 터치로 추가하거나, 기존 터널의 방위각과 박스를 수정할 수 있습니다.
  - 수정된 데이터는 `[JSON 백업]` 버튼을 통해 다운로드 폴더에 JSON 파일로 내보낼 수 있으며, 다른 기기로 복원/공유할 수 있습니다.
- **바이너리 에셋 직접 재생성**:
  - `tools/build_tunnel_asset.py` 스크립트를 사용하여 커스텀 터널 SQLite DB를 읽어 `app/src/main/assets/tunnels_kr.bin` 에셋으로 다시 패킹할 수 있습니다.

---

## ⚠️ 이슈(Issues) 및 Pull Request(PR) 정책

- **이슈 (Issues)**:
  - 버그나 개선 아이디어를 커뮤니티 구성원들과 공유하고 토론하는 공간으로 열어둡니다.
  - 다만 메인테이너가 모든 이슈를 직접 해결하거나 빠르게 답변하기 어려울 수 있으니 양해 바랍니다.
- **Pull Request (PR)**:
  - 메인테이너의 정기적 코드 검토 및 릴리즈 계획이 없으므로, **PR을 제출하시기보다는 Fork한 본인의 저장소에 반영하여 사용하시는 것을 권장**합니다.

---

자유롭게 Fork하여 더 멋진 AutoTunnel로 발전시켜 보세요! 🚗💨
