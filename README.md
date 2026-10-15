# Douyin İndirici

Douyin'deki (Çin TikTok'u) videoları ve resimleri bilgisayarına indiren, Türkçe arayüzlü Windows programı. Douyin hesabı gerekmez.

- **Tek gönderi:** bir videoyu ya da bir resim albümünü indirir.
- **Profilin tamamı:** bir kişinin bütün gönderilerini toplu indirir. Video linki verirsen o videoyu paylaşanın profilini indirir.
- **Kalite seçimi:** en yüksek, 1080p, 720p, 540p ya da en düşük.
- **İçerik seçimi:** video ve resim, sadece video ya da sadece resim.
- Filigransız indirir. Hareketli fotoğrafların (Live Photo) videosunu da alır.
- Daha önce inen dosyaları tekrar indirmez; yarıda kalan işe kaldığı yerden devam edebilirsin.

## İndirme ve kurulum

1. [Releases](../../releases) sayfasından en yeni `DouyinIndirici.exe` dosyasını indir.
2. Çift tıklayıp aç. Kurulum gerekmez.
3. İlk açılışta Windows **"Bilgisayarınız korundu"** diyebilir: **Ek bilgi → Yine de çalıştır**. (Program imzalı olmadığı için bu uyarı çıkar.)

Gereken tek şey Microsoft Edge (Windows 10/11'de zaten yüklü) ya da Google Chrome.

## Kullanım

1. Douyin'de beğendiğin videoyu ya da profili aç, adres çubuğundaki linki kopyala.
   (Telefondaki Douyin uygulamasından "paylaş → linki kopyala" ile gelen Çince metni de olduğu gibi yapıştırabilirsin.)
2. Programdaki kutuya yapıştır. Program linkin türünü kendisi anlar.
3. "Sadece bu gönderi" ya da "Profilin tamamı"nı seç, ayarları yap, **İndirmeyi başlat**'a bas.

İndirme sırasında bir Edge penceresi açılıp Douyin sayfasını kendisi kaydırır. **O pencereyi iş bitene kadar kapatma.**

Dosyalar varsayılan olarak `İndirilenler\Douyin\<kişinin adı>\` klasörüne iner:

```
İndirilenler\Douyin\Kişinin Adı\
    2026-10-01_video başlığı_7412345678901234567.mp4
    2026-10-03_resimli gönderi_7412345678901234568\
        01.jpg
        02.jpg
        02_hareketli.mp4
```

## Sık sorulanlar

**"Douyin doğrulama istiyor" yazıyor.**
Douyin bazen robot kontrolü (kaydırmalı bulmaca) gösterir. Açılan Edge penceresinde bulmacayı çöz; program bekler ve sonra kendiliğinden devam eder.

**Profilin sadece bir kısmı indi.**
Douyin, giriş yapmayanlara bazı profillerin sadece ilk gönderilerini gösteriyor. Programın altındaki **Douyin'e giriş yap** ile bir kez giriş yaparsan (Douyin uygulamasıyla QR kod okutarak) sonraki indirmelerde hepsi gelir. Giriş bilgisi sadece senin bilgisayarında, programın kendi tarayıcı profilinde durur. Aynı linkle tekrar başlattığında daha önce inenler atlanır.

**Videolar bilgisayarda açılmıyor.**
"Her oynatıcıda açılsın" kutusu işaretli kalsın (varsayılan). İşareti kaldırırsan bazı videolar H.265 biçiminde iner; Windows'ta bunları açmak için HEVC eklentisi ya da VLC gerekir.

**Program nerede ayar tutuyor?**
`%LOCALAPPDATA%\DouyinIndirici` klasöründe. Silersen ayarlar ve Douyin girişi sıfırlanır.

## Nasıl çalışıyor?

Douyin, programların doğrudan istek atmasını imza ve doğrulama sistemleriyle engelliyor. Bu program onun yerine bilgisayarındaki gerçek Edge tarayıcısını açıyor, sayfayı senin açacağın gibi açıyor ve sayfanın kendi yüklediği gönderi listesini yakalayıp dosyaları oradan indiriyor.

Arayüz, programın kendi içinde çalışan küçük bir yerel sunucu üzerinden Edge'in "uygulama penceresi"nde açılır. Sunucu sadece bu bilgisayardan erişilebilir.

## Geliştirme

```bash
pip install -r requirements.txt
python -m playwright install chromium   # sadece testler için
python main.py                          # programı çalıştır

python -m unittest tests.test_units                                   # birim testleri
DOUYIN_INDIRICI_BROWSER=chromium python -m unittest tests.test_integration   # uçtan uca testler (sahte Douyin)
```

Her `main` güncellemesinde GitHub Actions Windows'ta testleri gerçek Edge ile çalıştırır, `.exe`'yi derler ve derlenmiş `.exe`'nin açılıp Edge'i sürebildiğini dener. Testler geçerse `.exe`, `douyin_indirici/__init__.py` içindeki sürüm numarasıyla Releases sayfasına konur.

## Lisans ve teşekkür

MIT. Video kalitesi seçimi ve resim adresi mantığı [jiji262/douyin-downloader](https://github.com/jiji262/douyin-downloader) (MIT) projesinden uyarlanmıştır.

Kişisel kullanım içindir. İndirdiğin içeriklerin telif hakları sahiplerine aittir; başkalarının içeriğini izinsiz yeniden yayınlama.
