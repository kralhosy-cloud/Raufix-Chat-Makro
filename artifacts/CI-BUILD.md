# Gerçek mod jar — GitHub Actions ile derlendi

| | |
|---|---|
| Dosya | `chat-macro-studio-1.0.0.jar` |
| Boyut | 224.243 bayt |
| sha256 | `6579e501517d6e98cda46ba5a5c5409581b6a3fe2abf59316e8bea3bf39a8502` |
| Derleyen | GitHub Actions, run [43038665948](https://github.com/kralhosy-cloud/Raufix-Chat-Makro/actions/runs/43038665948) — `BUILD SUCCESSFUL in 1m 19s` |
| Kopya | `ci-artifacts` dalı, commit `96ff86d` |

## Ne olduğu

Loom ile **gerçek Minecraft 1.21.11** karşısında derlendi ve **intermediary** namespace'e
remap edildi — yani oyunun içine atılıp çalıştırılabilecek bir Fabric client modu.
Kapalı-devre (core-only) jar değil: 53'ten fazla `net/minecraft` referansı bytecode'da duruyor.

```
META-INF/MANIFEST.MF
  Fabric-Minecraft-Version: 1.21.11
  Fabric-Loader-Version:    0.19.3
  Fabric-Loom-Version:      1.20.0-alpha.27
  Fabric-Mapping-Namespace: intermediary
  Fabric-Loom-Mixin-Remap-Type: static
fabric.mod.json  -> id chatmacro_studio, environment client,
                    entrypoint dev.chatmacro.studio.client.ChatMacroStudioClient,
                    depends: minecraft 1.21.11, fabricloader >=0.19.3, fabric-api *, java >=21
assets/chatmacro_studio/icon.png
assets/chatmacro_studio/lang/{en_us,tr_tr}.json
```

İçerik: 120 dosya, 475.628 bayt açılmamış toplam.

## Kurulum

1. Fabric Loader **0.19.3+** ve **Minecraft 1.21.11** için bir profil kurun.
2. **Fabric API**'yi `mods/` klasörüne koyun (zorunlu bağımlılık).
3. Bu jar'ı `mods/` klasörüne kopyalayın.
4. Oyunda **Sağ Shift** stüdyoyu açar; `/chatmacro ui` da aynı işi yapar.

## Derlemede çözülen gerçek API farkları

Hepsi CI'da `javap` ile yarn build.6 jar'ından okundu, tahmin edilmedi:

| Eski (kaynakta) | 1.21.11 |
|---|---|
| `mouseClicked(double,double,int)` | `mouseClicked(Click, boolean)` |
| `keyPressed(int,int,int)` | `keyPressed(KeyInput)` |
| `KeyBinding#matchesKey(int,int)` | `matchesKey(KeyInput)` |
| `ClickableWidget#onPress()` | `onClick(Click, boolean)` |
| `ClickableWidget#isVisible()` | `visible` alanı |
| `NarrationMessageBuilder#addDefault()` | `put(NarrationPart.TITLE, …)` |
| `DrawContext#drawBorder(...)` | yok → 4 kenarı `fill` ile çiz |
| `CommandBuildContext` | `CommandRegistryAccess` |
| `KeyBinding(... String category)` | `KeyBinding.Category` kaydı |
| `MinecraftClient#isConnectedToRealms()` | `ServerInfo#isRealm()` |
| Gradle 8.14.2 / Java 21 | Gradle **9.9.0** / build JVM **Java 25** |

## Elle tekrar üretmek

```bash
git clone https://github.com/kralhosy-cloud/Raufix-Chat-Makro
cd Raufix-Chat-Makro
git checkout arena/b460c96e-raufix-chat-makro
# Actions -> "build" -> Run workflow, ya da:
git push   # her push build'i tetikler
# çıktı: ci-artifacts dalındaki chat-macro-studio-1.0.0.jar
```

Not: `gradle.properties`'teki `yarn_mappings`, `loom_version`, `fabric_version`
CI'da `scripts/resolve-fabric-versions.py` tarafından gerçek ağdan çözülür.
Yerelde internet varsa aynı script elle de çalıştırılabilir.
