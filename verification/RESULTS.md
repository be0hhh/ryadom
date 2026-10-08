# Локальная проверка «Рядом»

- `./gradlew :server:test :app:testDebugUnitTest`: exit0, server10/10 и Android12/12, failures0/errors0. См. `final-tests.log`.
- `./gradlew :app:lintDebug :app:assembleDebug`: exit0. Lint: 0 ошибок, 14 предупреждений. См. `apk-build.log` и `app/build/reports/lint-results-debug.html`.
- TDD: сервер10 ожидаемых failures на501-заглушках; Android8 тестов/5 failures на отсутствующем поведении. Дополнительные регрессии: устаревшие callbacks и lease с задержанным HTTP дали RED, затем GREEN.
- Независимый read-only review: три Important исправлены (ownership callbacks, независимый lease-watchdog, длинная/короткая стороны screencast). Контракт разрешений проверен по merged manifest и исходникам закреплённого SDK; совместимость с Android10 исправлена по результату lint.
- APK arm64-v8a, debug0.1.0, 78370477 байт. SHA256: `1a38e823b991b18b902c769d32df711a5577c8f0757e9df8e596b1091301d9f0`.
- Не запускались: серверы, Docker containers, Tailscale, браузер, эмулятор, телефоны, live/provider и сетевые проверки сервиса. Git-операций не было.
- Реальный мод TikTok и слышимость на втором A54 остаются UNRUN. Индикатор локального сигнала не заменяет эту проверку.
- Предупреждения lint: обновления закреплённых зависимостей, стиль manifest/API и отсутствие ChromeOS ABI; не скрыты baseline/suppress-all.
- SDK/JDK/Gradle скачаны в `.tools`, штатная системная Java8 не менялась. Прокси использует существующие env-параметры без сохранения учётных данных в проекте.

## Launcher run.sh

`python3 -m unittest discover -s scripts -p test_launcher.py -v`: exit0, 12/12 PASS; `verification/launcher-green.log`.

`bash -n run.sh deploy/setup.sh deploy/start.sh deploy/stop.sh scripts/bootstrap.sh`: exit0. `./run.sh --help`: exit0.

Изолированные проверки; реальная активация WSL/Tailscale/LiveKit и provider/device proof UNRUN. Основные файлы: run.sh, scripts/launcher.py, scripts/test_launcher.py, README.md, .gitignore. APK и Android/server sources не менялись.
