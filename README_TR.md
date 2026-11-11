# Chat Macro Studio

Minecraft Java Edition **1.21.11** için bir Fabric **istemci** modu: sohbet
makrolarını profiller halinde yönetir; arayüz, tik tabanlı zamanlayıcı ve dosya
depolama içerir.

> **Önce `KNOWN_LIMITATIONS.md` dosyasını okuyun.** Bu depo, Fabric ve Mojang
> depolarına erişimi olmayan bir ortamda yazıldı; bu yüzden `./gradlew build`
> burada hiç çalıştırılamadı ve **derlenmiş bir jar yok**.
> `src/main/java/dev/chatmacro/studio/client/` altındaki her dosya gerçek
> Minecraft API'sine karşı doğrulanmadı. Ayrıca `BUILD.md` ve `TEST_REPORT.md`.

---

## Ne yapar

Profiller oluşturursunuz. Her profilin kendi makro listesi vardır. Bir makro ya
bir sohbet mesajı ya da bir komuttur; *n* saniyede bir, istenirse bir başlangıç
gecikmesinden sonra tekrarlanır. **Başlat**'a basarsınız; mod, mesajı siz
yazmışsınız gibi istemcinin kendi sohbet yolundan gönderir.

Özellik seti bilinçli olarak bununla sınırlıdır. Kimseye bürünme, imzalı sohbet
manipülasyonu, paket enjeksiyonu, anti-spam atlatma, uzaktan komut, analitik ve
hiçbir ağ trafiği yoktur.

### Amaçlanan kullanım

Kendi hesabınız, kendi bilginiz dahilinde; tek oyunculu modda, kendi
sunucunuzda veya otomasyona izin veren bir sunucuda. **Sunucu kuralları buradaki
her özellikten üstündür.** Bir sunucu otomasyonu yasaklıyorsa bu modu orada
kullanmayın. Sunucu durmanızı söylüyorsa (atma, susturma, bekleme süresi,
anti-spam mesajı) mod duraklar veya geri çekilir ve bunu aşmaya asla çalışmaz.

Ekonomi ve takas komutları (`/pay`, `/money`, `/withdraw`, `/deposit`, `/buy`,
`/sell`, `/trade`, `/tpaccept`, `/tpyes`, `/ah`, `/warp`, `/give`, `/op`,
`/deop`, `/ban`, `/kick`) algılanır ve ilk gönderimde açık bir onay ister;
çünkü bir sunucu eklentisinin bu komutlarla ne yaptığını bu mod bilemez.

## Kurulum

1. Modu derleyin — `BUILD.md` dosyasına bakın. JDK 21 ve ağ erişimi gerekir.
2. Minecraft **1.21.11** için Fabric Loader **0.19.3** ve Fabric API kurun.
3. Derlenen `chat-macro-studio-<sürüm>.jar` dosyasını `.minecraft/mods/` içine koyun.
4. Oyunu başlatın.
5. Stüdyoyu açmak için **Sağ Shift** tuşuna basın.

### Tuş ataması ve "F30" hakkında

Gerçek varsayılan tuş **Sağ Shift**'tir ve *Ayarlar → Kontroller → Chat Macro
Studio* bölümünden yeniden atanabilir. Bu modla ilgili bazı belgelerde "F30"
geçer. Gerçek bir F30 tuşu yoktur: fiziksel klavyeler ve GLFW yalnızca F25'e
kadar tuş tanımlar, yani F30'a atama yapmak aslında hiçbir şeye atama yapmamak
demektir. "F30" yalnızca eylemin etiketi olarak korunur ve aşağıdaki komutlar
bunu karşılar:

| Eylem | Varsayılan |
| --- | --- |
| Stüdyoyu aç | **Sağ Shift** (yeniden atanabilir) |
| Acil durdurma, tüm makrolar | **Sağ Ctrl** (yeniden atanabilir) |

| Komut | Etkisi |
| --- | --- |
| `/chatmacro ui` | stüdyoyu açar (tuşla aynı) |
| `/chatmacro f30` | `/chatmacro ui` takma adı |
| `/chatmacro start` / `pause` / `resume` / `stop` | çalıştırma denetimi |
| `/chatmacro list` | etkin profilin makrolarını listeler |
| `/chatmacro save` | yapılandırmayı hemen kaydeder |

Bilinçli olarak bir `GLFW_KEY_F30` sabiti **yoktur**: GLFW yalnızca `F25`'e
kadar tuş tanımlar. Her iki atama da gerçek sabitler kullanır
(`GLFW_KEY_RIGHT_SHIFT`, `GLFW_KEY_RIGHT_CONTROL`).

Acil durdurma, arayüz tuşundan ayrı bir atama ve ayrı bir kod yoludur: arayüz
tuşuna basmak çalışan bir oturumu asla durdurmaz, durdurmak da arayüzü açmaz.

## Güvenlik davranışı

Bunlar uygulanmıştır, niyet beyanı değildir:

* **Kendiliğinden başlamaz.** Bir çalıştırma yalnızca açık bir Başlat ile
  (arayüzden veya `/chatmacro start` ile) başlar. Profil bazlı sunucu kapsamları
  ve profil bazlı "otomatik başlat" anahtarı isteğe bağlıdır ve varsayılan
  olarak kapalıdır.
* **Yeni makrolar kapalı oluşturulur.** Bir makroyu kaydetmek hiçbir şey
  göndermez.
* **Dünya ve oyuncu olmadan gönderim yok.** Ana menü, bağlantı kopması, ölüm,
  boyut değişimi ve kimlik değişimi çalıştırmayı durdurur veya bekletir.
* **Oturum nesli korumaları.** Her bağlanmada bir sayaç artar; eski bir
  oturumdan gelen geri çağrılar atılır, böylece yeniden bağlanma birikmiş
  gönderim patlaması yaratamaz.
* **Telafi gönderimi patlamaz.** Tikler kaybolduysa veya oyun duraklatıldıysa,
  kaçırılan tekrarlar düşürülür ve yalnızca bir sonraki gönderim planlanır.
* **Yerel bir gönderim bütçesi**: varsayılan olarak tüm makrolar toplamında 3
  saniyede bir gönderim. Bu, *sizin* istemciniz için bir güvenlik sınırıdır;
  sunucu sınırlarını aşmak değildir.
* **Çalıştırma asla görünmez kalmaz.** Başlat, durdur, duraklat, devam et ve
  zamanlayıcının kendi durdurmaları sohbet günlüğüne yazılır; böylece stüdyoyu
  kapattıktan sonra devam eden bir oturum yine de duyurulur. Ayarlar'dan
  kapatılabilir.
* **Dürüst algılama.** Mod yalnızca istemcinin görebildiğini algılar (bağlantı
  koptu, sohbet kapalı, gönderim reddedildi). Bir eklentinin sizi susturup
  susturmadığını tahmin etmek için sohbeti okumaz ve bunu yaptığını iddia etmez.
* **Asla geri almadığını söyler.** Durdurma bekleyen işi iptal eder; gönderilmiş
  mesajlar gitmiştir.

## Depolama

Her şey `config/chatmacro_studio/` altında yerel JSON'dur:

```
config/chatmacro_studio/config.json                       şema sürümü 1
config/chatmacro_studio/config.json.corrupt-<millis>      (yalnızca hasardan sonra)
config/chatmacro_studio/config.json.newer-version         (yalnızca daha yeni bir mod yazdıysa)
config/chatmacro_studio/exports/<ad>.cmsprofile           dışa aktarılan profiller
```

Yazmalar geçici bir dosyaya yapılır, atomik olarak değiştirilir ve
geciktirilir; istemci kapanırken dosya diske yazılır (flush). Bozuk bir dosya yedeklenir
ve bildirilir, asla sessizce üzerine yazılmaz; mod anlamadığı veriyi yok etmek
yerine salt okunur moda geçer. Ayrıntılar `docs/config-schema.md` içinde.

Bir profili dışa aktarmak `.cmsprofile` paketi üretir. `/login` şifresi gibi
sırlar varsayılan olarak maskelenir; ayrı ve onaylı bir eylem bunları açık
metinle aktarır. İçe aktarma önce o dizindeki paketleri listeler, profilin ve
içerdiği her makronun önizlemesini gösterir, ardından her zaman **kapalı**
makrolar ve **yeni** UUID'ler üretir. Aynı ada sahip bir profilin üzerine yazmak
açık onay gerektirir.

## Gizlilik

Token yok, oturum verisi yok, kimlik bilgisi yok, analitik yok, reklam yok,
güncelleyici yok, uzaktan komut yok, hiçbir telemetri yok. Bu modun yazdığı tek
şey kendi yapılandırma dizini, dışa aktarma klasörü ve kendi günlük
satırlarıdır. Günlükte kimlikler ve hata sınıfları bulunur, mesaj gövdeleri
asla bulunmaz. **Tanılama** ekranı modun kendisi hakkında bildirdiği her şeyi
gösterir: sayılar, şema sürümü, deponun salt okunur olup olmadığı ve maskelenmiş
bir sunucu adresi. Yalnızca görüntüler; gönder düğmesi yoktur ve hiçbir şey
makinenizden çıkmaz.

## Görünüm ve erişilebilirlik

`dev.chatmacro.studio.theme` içindeki anlamsal belirteçlerle tanımlanan
mavi/gri/kırmızı palet — her öğe `tokens.accent()`, `tokens.danger()`,
`tokens.focusRing()` gibi değerleri okur; açık ve koyu temalar ile vurgu
renkleri tek bir yerde tanımlıdır, dağınık hex değerleri yoktur. Durum asla
yalnızca renkle aktarılmaz: açık/kapalı/çalışıyor/bekletiliyor durumlarının
her birinin bir metin etiketi ve ayrı bir şekli de vardır.

* Tab / Shift+Tab odağı taşır (Minecraft'ın kendi öğe dolaşımı), Enter etkinleştirir,
  Escape güvenle kapatır ve kaydedilmemiş düzenlemeleri atmadan önce sorar.
* Uzun listeler sanallaştırılmıştır (sınırlı çizim), yani 500 profil 500 öğe
  demek değildir.
* Animasyonlar 120–220 ms'dir, zamana bağlıdır ve bunları kapatan bir
  "azaltılmış hareket" ayarı vardır. Ekrandan daha uzun yaşayan zamanlayıcı veya
  dinleyici yoktur.

## Depo düzeni

```
src/main/java/dev/chatmacro/studio/
  ModInfo.java, Studio.java         giriş noktaları ve cephe (facade)
  model/                            Macro, Profile, kapsamlar, doğrulama
  sched/                            MacroScheduler, Clock, politika
  dispatch/                         ChatDispatchAdapter ve sonuçları
  config/                           codec, depo, migrasyonlar, ayarlar
  store/                            ByteStore, dosya ve bellek içi
  repo/                             profil ve makro depoları
  io/                               paketler, hassas komutlar, maskeleme
  theme/, ui/                       belirteçler, renkler, sanal liste, animasyon
  client/                           Minecraft katmanı (15 dosya, burada hiç derlenmedi)
src/main/resources/
  fabric.mod.json
  assets/chatmacro_studio/lang/{en_us,tr_tr}.json
  assets/chatmacro_studio/icon.png
src/test/java/dev/chatmacro/studio/  114 test, bağımlılıksız çalıştırıcı
scripts/                             sürüm eşitleme, wrapper kontrolü, test çalıştırıcı
tools/offline-javac/                 testleri çevrimdışı çalıştıran araç
tools/ui-preview/, ui-preview/       gerçek paletle çizilmiş önizleme PNG'leri
docs/config-schema.md
BUILD.md, TEST_REPORT.md, KNOWN_LIMITATIONS.md, THIRD_PARTY_NOTICES.md
```

`fabric.mod.json` `"environment": "client"` ve tek bir `client` giriş noktası
(`dev.chatmacro.studio.client.ChatMacroStudioClient`) bildirir. Alan modeli,
zamanlayıcı, kalıcılık ve tema katmanlarında **hiç** Minecraft importu yoktur;
onları oyun olmadan test edilebilir kılan şey budur.

## Ekran görüntüleri hakkında

`ui-preview/*.png` dosyaları **Minecraft ekran görüntüsü değildir**: çalışan
tema paleti, koddaki yerleşim sabitleri ve gerçek dil dosyalarıyla çevrimdışı
çizilmiş önizlemelerdir. Kontrast ve taşma hatalarını yakalamak için
kullanıldı (dört gerçek kusur bulundu ve düzeltildi) ama oyun içi davranışı
kanıtlamaz. `ui-preview/studio-walkthrough.gif` de aynı çizicinin beş durumu
art arda çizmesidir; ekran kaydı değildir. Ayrıntı: `ui-preview/README.md`,
`KNOWN_LIMITATIONS.md`.

## Lisans

MIT — `LICENSE` dosyasına bakın. Bu proje Mojang Studios veya Microsoft ile
bağlantılı değildir, onlar tarafından onaylanmamış veya desteklenmemiştir.
"Minecraft", Mojang Studios'un ticari markasıdır.
