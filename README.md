# Douyin İndirici

Douyin'deki (Çin TikTok'u) videoları ve resimleri indiren, Türkçe ve İngilizce arayüzlü **Windows programı** ve **Android uygulaması**. Douyin hesabı gerekmez.

- **Tek gönderi:** bir videoyu ya da bir resim albümünü indirir.
- **Profilin tamamı:** bir kişinin bütün gönderilerini toplu indirir. Video linki verirsen o videoyu paylaşanın profilini indirir.
- **Kalite seçimi:** en yüksek, 1080p, 720p, 540p ya da en düşük.
- **İçerik seçimi:** video ve resim, sadece video ya da sadece resim.
- Filigransız indirir. Hareketli fotoğrafların (Live Photo) videosunu da alır.
- Daha önce inen dosyaları tekrar indirmez; yarıda kalan işe kaldığı yerden devam edebilirsin.

## İndirme ve kurulum

### Windows
1. [Releases](../../releases) sayfasından en yeni `DouyinIndirici.exe` dosyasını indir.
2. Çift tıklayıp aç. Kurulum gerekmez.
3. İlk açılışta Windows **"Bilgisayarınız korundu"** diyebilir: **Ek bilgi → Yine de çalıştır**. (Program imzalı olmadığı için bu uyarı çıkar.)

Gereken tek şey Microsoft Edge (Windows 10/11'de zaten yüklü) ya da Google Chrome. Edge'in kurulumu bozuksa program Chrome'u ya da kendi indirdiği tarayıcıyı kullanır.

### Android
1. Telefondan [Releases](../../releases) sayfasını aç, `DouyinIndirici-<sürüm>.apk` dosyasına dokun.
2. Telefon "bilinmeyen kaynaktan yükleme" izni isterse ver, sonra **Yükle**.
3. Yeni sürümler eskisinin üzerine kurulur; ayarlar ve Douyin girişi silinmez.

Android 10 ve üstü gerekir.

## Kullanım

1. Douyin'de beğendiğin videoyu ya da profili aç, adres çubuğundaki linki kopyala.
   (Telefondaki Douyin uygulamasından "paylaş → linki kopyala" ile gelen Çince metni de olduğu gibi yapıştırabilirsin.)
2. Programdaki kutuya yapıştır. Program linkin türünü kendisi anlar.
3. "Sadece bu gönderi" ya da "Profilin tamamı"nı seç, ayarları yap, **İndirmeyi başlat**'a bas.

**Windows'ta** indirme sırasında bir Edge penceresi açılıp Douyin sayfasını kendisi kaydırır. **O pencereyi iş bitene kadar kapatma.**

**Android'de** her şey uygulamanın içinde olur, ayrı bir pencere açılmaz. En kolay yol: Douyin'de bir videoda **Paylaş → Douyin İndirici**; link kutuya kendiliğinden gelir. İndirme sürerken uygulamadan çıkabilir ya da ekranı kapatabilirsin; ilerleme bildirimde görünür.

Windows'ta dosyalar varsayılan olarak `İndirilenler\Douyin\<kişinin adı>\` klasörüne iner:

```
İndirilenler\Douyin\Kişinin Adı\
    2026-10-01_video başlığı_7412345678901234567.mp4
    2026-10-03_resimli gönderi_7412345678901234568\
        01.jpg
        02.jpg
        02_hareketli.mp4
```

Android'de galeride **DCIM › Douyin › <kişinin adı>** klasörüne iner. Galeri uygulamaları her alt klasörü ayrı albüm gösterdiği için resimli gönderiler alt klasöre değil kişinin klasörüne `<gönderi>_01.jpg` adıyla kaydedilir.

## Sık sorulanlar

**"Douyin doğrulama istiyor" yazıyor.**
Douyin bazen robot kontrolü (kaydırmalı bulmaca) gösterir. Windows'ta açılan Edge penceresinde, Android'de uygulamada öne gelen Douyin ekranında bulmacayı çöz; program bekler ve sonra kendiliğinden devam eder. Telefonda bulmaca küçük görünürse iki parmakla yakınlaştırabilirsin.

**Profilin sadece bir kısmı indi.**
Douyin, giriş yapmayanlara bazı profillerin sadece ilk gönderilerini gösteriyor. Programın altındaki **Douyin'e giriş yap** ile bir kez giriş yaparsan sonraki indirmelerde hepsi gelir (bilgisayarda Douyin uygulamasıyla QR kod okutarak, telefonda telefon numarasıyla). Giriş bilgisi sadece senin cihazında, programın kendi tarayıcısında durur. Aynı linkle tekrar başlattığında daha önce inenler atlanır.

**Videolar bilgisayarda açılmıyor.**
"Her oynatıcıda açılsın" kutusu işaretli kalsın (varsayılan). İşareti kaldırırsan bazı videolar H.265 biçiminde iner; Windows'ta bunları açmak için HEVC eklentisi ya da VLC gerekir.

**Program nerede ayar tutuyor?**
Windows'ta `%LOCALAPPDATA%\DouyinIndirici` klasöründe. Silersen ayarlar ve Douyin girişi sıfırlanır. Android'de uygulamanın kendi verisinde (Ayarlar › Uygulamalar › Douyin İndirici › Depolama › Verileri temizle).

## Dil ekleme ve yazıları değiştirme

Programdaki bütün yazılar `ceviriler/` klasöründeki dosyalarda durur: `tr.json` (Türkçe), `en.json` (İngilizce). Windows ve Android aynı dosyaları kullanır.

- **Bir yazıyı değiştirmek:** dosyayı GitHub'da aç, kalem simgesine bas, tırnak içindeki metni değiştir, kaydet. Soldaki anahtar (`"ui.start"` gibi) değişmemeli; `{n}`, `{name}` gibi süslü parantezli yerler aynen kalmalı.
- **Yeni dil eklemek:** `en.json`'ı kopyalayıp dil koduyla adlandır (ör. `ru.json`), `"_language_name"` değerini dilin adı yap (ör. `"Русский"`), metinleri çevir.
- Kaydettiğinde GitHub yeni `.exe` ve `.apk`'yı kendisi derler. Bir dosyada eksik kalan yazı varsa program onun İngilizcesini gösterir; derleme sırasındaki denetim, `{n}` gibi bir yerin unutulmasını yakalar.
- Uygulamanın telefondaki adı da bu dosyalardan (`ui.app_title`) gelir.

## Nasıl çalışıyor?

Douyin, programların doğrudan istek atmasını imza ve doğrulama sistemleriyle engelliyor. Bu program onun yerine gerçek bir tarayıcı açıyor, sayfayı senin açacağın gibi açıyor ve sayfanın kendi yüklediği gönderi listesini yakalayıp dosyaları oradan indiriyor. Windows'ta bu tarayıcı bilgisayarındaki Edge; Android'de uygulamanın içine gömülü, görünmeyen bir tarayıcı (telefonun kendi WebView'i).

Arayüz (`douyin_indirici/ui/index.html`) iki sürümde de aynı dosyadır. Windows'ta programın içindeki küçük bir yerel sunucudan (sadece bu bilgisayardan erişilebilir) Edge'in uygulama penceresinde açılır; Android'de uygulamanın içinden yüklenir.

Link tanıma, kalite seçimi, resim seçimi ve dosya adlandırma kuralları Windows'ta Python, Android'de Kotlin ile yazılı. İkisinin birebir aynı davrandığı, `tests/vectors/` klasöründeki ortak test verileriyle hem bilgisayarda hem telefonda (emülatörde) denenir.

## Geliştirme

```bash
pip install -r requirements.txt
python -m playwright install chromium   # sadece testler için
python main.py                          # programı çalıştır

python -m unittest tests.test_units                                   # birim testleri
DOUYIN_INDIRICI_BROWSER=chromium python -m unittest tests.test_integration   # uçtan uca testler (sahte Douyin)
```

Android: `android/` klasörü (Kotlin). `cd android && ./gradlew testDebugUnitTest assembleDebug`.

Her güncellemede GitHub Actions:
- **Windows'ta:** testleri gerçek Edge ile çalıştırır, `.exe`'yi derler; derlenmiş `.exe`'yi hem normal hem Edge'i bilerek bozulmuş halde dener.
- **Android'de:** birim testlerini çalıştırır, APK'yı derler, uygulamayı bir Android emülatöründe sahte bir Douyin sunucusuna karşı uçtan uca dener (düğmelere basarak: profil indirme, doğrulama ekranı, giriş uyarısı, galeriye kayıt, paylaş menüsü, durdurma, ekranın yeniden kurulması). Ekran görüntüleri `ci-sonuc-<dal>` dalına konur.
- İkisi de geçerse `main` dalında `.exe` ve `.apk`, `douyin_indirici/__init__.py` içindeki sürüm numarasıyla Releases sayfasına konur.

## Lisans ve teşekkür

MIT. Video kalitesi seçimi ve resim adresi mantığı [jiji262/douyin-downloader](https://github.com/jiji262/douyin-downloader) (MIT) projesinden uyarlanmıştır.

Kişisel kullanım içindir. İndirdiğin içeriklerin telif hakları sahiplerine aittir; başkalarının içeriğini izinsiz yeniden yayınlama.
