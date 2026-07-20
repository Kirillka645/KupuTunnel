# KupuTunnel

Системный **VPN** для Android на **Xray-core** (как Happ) + UI/TUN-паттерн в духе Hiddify.

**https://github.com/Kirillka645/KupuTunnel**

## Протоколы

| Протокол | Ссылка |
|----------|--------|
| **VLESS** + Reality / TLS / Vision | `vless://` |
| **VMess** | `vmess://` |
| **Trojan** | `trojan://` |
| **Shadowsocks** | `ss://` |
| **Socks** | `socks://` / `socks5://` |

## Как работает VPN

1. Парсеры тянут публичные share-links  
2. TCP-пинг → сортировка  
3. **Подключить** → Android `VpnService` (TUN) + **Xray-core** (`libv2ray`)  
4. Весь трафик устройства идёт через выбранный узел  

## Возможности

- ⚡ **Лучший VPN** — найти и подключить самый быстрый  
- 🔭 Мега-скан всех парсеров  
- 📶 Профили Wi‑Fi / LTE  
- ⭐ Избранное + кэш  
- 🔔 Foreground notification с отключением  

## Источники

- igareck / vpn-configs-for-russia (VLESS Reality mobile)  
- vansFenix WVFMINI  
- MatinGhanbari, EbraSha, TGParse, 0xRadikal  

## Сборка

```bash
# нужен libv2ray.aar в app/libs/ (из 2dust/AndroidLibXrayLite releases)
./gradlew assembleRelease
```

## Дисклеймер

Публичные ноды нестабильны и могут быть небезопасны.  
Не используйте для банков и чувствительных данных. MIT.

## Лицензия

MIT · Xray-core / AndroidLibXrayLite — их лицензии.
