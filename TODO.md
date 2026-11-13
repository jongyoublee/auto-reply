# TODO

보류 중인 작업 (ponytail-audit 결과 중 아직 하지 않은 항목)

- [ ] **테스트 추가**: 하루 1회 전송 규칙(`SendLog`)과 방해금지 시간 계산(`ReplySettings.isDndActive`)
  - JUnit 설정 추가, `norm`과 `isDndActive`를 `Context` 없이 테스트할 수 있게 구조 조정
  - 확인할 케이스: `+8210…` = `010…`, 다른 항목은 차단 안 됨, 어제 기록은 차단 안 됨, 야간 구간(22:00~08:00)의 금요일 23:00 / 토요일 07:00
- [ ] **`MainActivity` 분리**: 이미지 처리 코드(`saveDraftImage` ~ `rotateDraft`, `fitJpeg`, EXIF)를 `ImageUtil.kt`로 이동
