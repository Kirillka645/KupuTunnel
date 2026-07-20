# KupuTunnel

Android-радар **бесплатных VPN-конфигов**: парсит открытые списки, TCP-пингует узлы и одной кнопкой открывает **лучший** в v2rayNG / Hiddify / NekoBox.

**https://github.com/Kirillka645/KupuTunnel**

Сестра [KupuProxy](https://github.com/Kirillka645/KupuProxy) (MTProto для Telegram) — тот же UX, но для VLESS / Reality / Trojan / Hy2.

## Возможности

| | |
|--|--|
| **⚡ Лучший VPN** | Загрузка → пинг → авто-подключение к самому быстрому |
| **🔭 Мега-скан** | Все парсеры сразу, live-список живых |
| **Парсеры** | vansFenix, igareck RU Mobile, MatinGhanbari, EbraSha, TGParse, 0xRadikal |
| **CDN-зеркала** | jsDelivr / githack / ghproxy |
| **Профили Wi‑Fi / LTE** | Разный concurrency и early-stop |
| **Избранное ⭐** | + кэш последних рабочих |
| **Подключение** | Deep-link / share в v2rayNG, Hiddify, NekoBox |

## Источники (парсеры)

1. [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia) — VLESS Reality White Lists (Mobile)
2. [vansfenix/vansFenix WVFMINI](https://gitverse.ru/vansfenix/vansFenix) — сборка @wildVF
3. [MatinGhanbari/v2ray-configs](https://github.com/MatinGhanbari/v2ray-configs) — filtered VLESS / Hy2
4. [ebrasha/free-v2ray-public-list](https://github.com/ebrasha/free-v2ray-public-list)
5. [Surfboardv2ray/TGParse](https://github.com/Surfboardv2ray/TGParse)
6. [0xRadikal/Free-v2ray-Configs](https://github.com/0xRadikal/Free-v2ray-Configs)

## Как пользоваться

1. Установи **v2rayNG**, **Hiddify** или **NekoBox**
2. Открой KupuTunnel → **Лучший VPN · одной кнопкой**
3. Или выбери отдельный парсер / тапни любой узел в списке

TCP-пинг = «порт жив». Полный VPN-туннель поднимает клиент (Xray/sing-box).

## Сборка

```bash
./gradlew assembleRelease
```

Требуется Android SDK 35, JDK 17.

## Дисклеймер

Публичные бесплатные ноды **нестабильны** и могут быть **небезопасны**.  
Не используйте для банков, паролей и чувствительных данных.  
На свой страх и риск. Проект не связан с владельцами списков.

## Лицензия

MIT
