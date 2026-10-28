# PulseTrainer — инструкции для Claude Code

Личное приложение владельца (Сергей): тренировки на **Samsung Galaxy Watch Ultra 2** с пульсом от нагрудного датчика **Polar H10**, «умный тренер» на **Samsung-телефоне**, обмен данными с **Samsung Health** через Health Connect.
Репозиторий: `Sarmat0808/PulseTrainer` (ветка `main`). Общаться с владельцем — **по-русски**, коротко и понятно.

## Стек
- Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Gradle 8.7 (**Gradle Wrapper в репозитории нет**), JDK 17.
- UI: Jetpack Compose (Material3 на телефоне, Wear Compose Material 1.3.1 на часах), compose-bom 2024.06.00, compiler extension 1.5.14.
- Android: compileSdk/targetSdk 34, minSdk 28 (телефон, core) и 30 (часы).
- Библиотеки: Health Connect `connect-client 1.1.0-alpha07`, Health Services (часы), Wearable Data Layer (`play-services-wearable`), WorkManager, osmdroid (карта маршрута), Wear Tiles / ProtoLayout, Watch-face complications.
- Тестов в проекте нет. Проверка = успешная сборка в CI + проверка на реальных устройствах.

## Структура папок (`C:\PulseTrainer`)
```
C:\PulseTrainer
├── build.gradle.kts, settings.gradle.kts, gradle.properties   — корень Gradle (модули :core, :wear, :mobile)
├── .github/workflows/build.yml     — CI: сборка и публикация релиза build-N
├── keystore/pulsetrainer.jks       — общий ключ подписи (часы и телефон ОБЯЗАНЫ быть подписаны одним ключом)
├── README.md                       — описание функций для пользователя
├── core/                           — общая библиотека (чистая логика, без UI), пакет fi.sarmat.pulsetrainer.core
│   └── src/main/java/.../core/
│       Coach.kt (готовность, советы дня), Learn.kt (самообучение: форма Banister CTL/ATL/TSB, TDEE, личный коэффициент восстановления),
│       Insights.kt (разбор тренера, план сна), Nutrition.kt (нормы ккал/белка/воды, база продуктов), Physiology.kt (BMR Миффлина, зоны, Keytel, kcal силовой),
│       Energy.kt, Health.kt, Model.kt (Workout, Segment, Lap…), Json.kt (обмен часы↔телефон), Report.kt (отчёты и промпты для ИИ),
│       Review.kt (оценка тренировки), Ecg.kt, Gpx.kt, SportIcons.kt
├── mobile/                         — приложение для телефона (applicationId fi.sarmat.pulsetrainer)
│   └── src/main/java/.../
│       MainActivity.kt, TodayScreen.kt («Сегодня»), CoachScreen.kt («Тренер»), WorkoutsScreen.kt, NutritionScreen.kt,
│       PhoneStore.kt (общее состояние, insights(), advise(), recovery()), HealthData.kt / HealthSync.kt (Health Connect),
│       FoodStore.kt / FoodSync.kt, Water.kt, Reminders.kt, Balance.kt (съедено vs потрачено), MyPlan.kt («Мой план» сон/еда/тренировки),
│       InsightsUi.kt, FullReport.kt + Share.kt (полный отчёт для ИИ), Backup.kt, RouteMap.kt, Widgets.kt, Reorder.kt
├── wear/                           — приложение для часов (тот же applicationId — это нужно для связи с телефоном)
│   └── src/main/java/.../
│       MainActivity.kt, Screens.kt, WorkoutScreen.kt (экраны тренировок + Always-On), WorkoutEngine.kt (подходы, круги, ккал, пульс),
│       WorkoutService.kt, HrSensor.kt (Polar H10 / датчик часов), Sensors.kt, Passive.kt (фоновый сбор), PhoneLink.kt (Data Layer),
│       Storage.kt, WaterNotify.kt, EcgScreen.kt, StressScreen.kt, Tiles (FavTiles.kt, HealthTiles.kt), Complications.kt, Common.kt
```

## Команды
Основной путь — **сборка в GitHub Actions**: `git push` в `main` → CI (~7–8 мин) собирает и публикует релиз `build-N` с файлами `PulseTrainer-phone.apk` и `PulseTrainer-watch.apk`.

```
# Сборка локально (нужны JDK 17, Android SDK 34 и Gradle 8.7 в PATH; ANDROID_HOME задан)
gradle --no-daemon assembleRelease
#   APK: mobile\build\outputs\apk\release\*.apk  и  wear\build\outputs\apk\release\*.apk

# Запуск = установка APK. Телефон по USB/Wi-Fi:
adb install -r mobile\build\outputs\apk\release\<файл из этой папки>.apk
# Часы (ADB по Wi-Fi) или приложение WearLoad на телефоне:
adb -s <IP:порт> install -r wear\build\outputs\apk\release\<файл из этой папки>.apk

# Статус сборки и релизов (нужен gh auth login)
gh run list --limit 3
gh release list --limit 3
gh release download build-N -p "*.apk"
```
Тестов нет: не выдумывай команды `test`. Корректность проверяй сборкой (в CI ошибки Kotlin выводятся как `::error::`) и разбором логики вручную.

## Правила работы с кодом
1. **Версии.** При каждом изменении, которое пойдёт на устройство, увеличивай `versionCode` и `versionName` в `mobile/build.gradle.kts` и `wear/build.gradle.kts` одновременно. Формат: `"6.8 телефон"` и `"6.8 часы"` (суффикс нужен, чтобы в установщиках было видно, какой APK для чего). Текущая версия: 6.8 (versionCode 68).
2. **Один источник расчётов.** Числа (ккал, готовность, восстановление, вода) считаются в `core` и используются везде. Не дублируй формулы в UI и не допускай противоречий между часами, телефоном и Samsung Health.
3. **Samsung Health — эталон** для сна, пульса покоя, шагов, активных калорий. Приложение подстраивается под него, а не спорит.
4. **Минимальный интерфейс**: главное сверху, подробности — в раскрывающихся блоках («спойлерах»). Крупные читаемые шрифты. Советы короткие и понятные: как спать, как есть, как тренироваться.
5. **Экономия батареи** на часах: фоновый сбор пачками, без лишних пробуждений, Always-On экран тренировки с крупными цифрами.
6. **Не ломай связь часы↔телефон**: `applicationId` одинаковый, подпись общая, формат Json в `core/Json.kt` менять только обратно-совместимо (новые поля — необязательные).
7. **Языки и тексты UI — русские.** Код, имена, комментарии — как в проекте (комментарии на английском допустимы).
8. **Kotlin-нюансы, на которых уже падала сборка:** smart cast на свойствах из другого модуля невозможен — копируй в локальную `val`.
9. Перед правкой читай файл целиком (файлы большие: `Nutrition.kt`, `CoachScreen.kt`, `NutritionScreen.kt` — 55 КБ+). Не сокращай файлы, не оставляй заглушки и `TODO`.
10. Коммиты: короткое сообщение с номером версии, например `6.9: …`. Пуш в `main` запускает релизную сборку, поэтому объединяй мелкие правки в один пуш.
11. **Безопасность.** В `keystore/pulsetrainer.jks` и в `build.gradle.kts` лежат ключ и пароль подписи; не выводи их в ответах и не меняй ключ (иначе обновление не встанет поверх установленного приложения, а часы перестанут видеть телефон). Если репозиторий станет закрытым, ссылки на скачивание APK у посторонних перестанут работать.
12. Не удаляй файлы и не меняй видимость репозитория без прямой просьбы владельца.

## Как общаться с владельцем
- В начале каждого ответа называй, на какую модель переключиться и какой уровень усилий (Низкий / Средний / Высокий / Сверхвысокий / Макс — как в меню приложения), и жди слова «готово». Для простых задач советуй лёгкую модель.
- Коротко: что сделано, что установить (какой build-N), что проверить. Без пересказа шагов.
- Если пользователь присылает скриншоты с часов или телефона — сверяй их с кодом экрана (`WorkoutScreen.kt`, `TodayScreen.kt` и др.).

## Что уже сделано (до версии 6.8)
- Экраны тренировок на часах для всех видов (силовая, кардио, беговая дорожка, орбитрек, GPS-активности, интервалы), увеличенный Always-On.
- Готовность согласована с Samsung: ночной пульс, потолок «средняя», пока осталось >12 ч восстановления; «Отдых после тренировки».
- Самообучение: форма Banister (старт со средней нагрузки), адаптивный TDEE (смешивается с формулой с 7 дней еды, полностью с 14), личный коэффициент восстановления 0.75–1.30; профицит для набора +150 ккал.
- Карточки телефона: «Мой план», «Разбор тренера» (сон, еда, тренировки), баланс «съедено / потрачено», полный отчёт для ИИ.
- Известное ограничение: Samsung не отдаёт ВСР (HRV) через Health Connect; вода из сторонних приложений не видна в Samsung Health.
- Открытый вопрос: Play Protect ругается на APK из-за открытого ключа подписи в репозитории (решение — секретный ключ в GitHub Secrets либо закрытая раздача).
