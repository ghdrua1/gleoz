# Gleoz

글레오즈는 차량 안에서 오즈의 모션과 함께하는 펫 앱의 프로토타입입니다.

## 실행 환경

- Pleos Connect Emulator 2.1.0, Android Automotive OS API 34, 2560 × 1440, 160 dpi
- Android Studio에서 이 저장소의 `settings.gradle.kts`가 있는 폴더를 엽니다.
- 차량 앱 모듈은 `automotive`입니다. `mobile`과 `shared`는 프로젝트 템플릿에 포함된 모듈입니다.

## APK 실행

저장소의 `apk/` 폴더에 있는 APK를 Pleos 에뮬레이터에 설치하고 앱 목록에서 글레오즈를 엽니다.

```powershell
adb install -r apk/Gleoz_Gleoz_release.apk
```

이 APK는 release 빌드 변형을 개발용 debug 키로 서명한 에뮬레이터 시연용입니다. 상용 배포용 서명과 심사 검증은 별도로 필요합니다.

Android Studio JBR과 Android SDK를 설정한 뒤 프로젝트를 빌드할 수 있습니다.

```powershell
.\gradlew.bat :automotive:assembleRelease
```

Pleos Maven 저장소의 Vehicle SDK 의존성을 받으려면 네트워크 연결이 필요할 수 있습니다. 설치 후 주차(P) 및 주행 제한 해제가 확인되면 화면이 표시됩니다. 에뮬레이터의 분할 창으로 Driving view와 앱을 함께 볼 수 있습니다.

## 현재 시연 범위

- 시스템 라이트·다크 모드에 따른 낮·밤 배경
- 주차 중 마법과 차박 모션, 메뉴에서 여정·꾸미기·장면 선택
- 실제 에뮬레이터 기어 상태와 주행 제한 구독. D에서 화면 이용을 제한하고 D → P 후 주차 제한이 해제되면 귀가 모션을 재생
- 여정의 거리와 성장 단계는 시연용 고정 예시입니다. 주행 거리 기반 성장, Gleo 연동, 도어 이벤트 모션은 아직 구현 중입니다.

현재 프로토타입에는 장면 선택 화면의 일부 창 크기에서 미리보기와 하단 버튼이 가려지는 문제가 있습니다. 실제 차량, 모든 화면 크기와 주행 조건에서의 검증은 진행 중입니다.

시연 영상은 `video/Gleoz_demo.mp4`에 있습니다. 녹화는 디버그 빌드로 진행했습니다. 영상의 기어 조작은 Pleos Virtual Console을 사용했습니다.

Asta Sans 글꼴의 라이선스는 `licenses/AstaSans-OFL.txt`에 포함했습니다.
