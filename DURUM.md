# DURUM — Douyin İndirici

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-11-05 · Son sürüm: **v1.2.0** (2026-11-05)

## Ne yapıyor
Douyin hesabı olmadan bir profilin tüm video/görsellerini toplu (kalite seçimiyle) ya da tek
videoyu indirir. Türkçe arayüz; mevcut açık kaynak Douyin indirme mantığını sarar.

## Teknik
- Windows: Python (`main.py`, `douyin_indirici/`), EXE olarak dağıtılır; `__version__` → `douyin_indirici/__init__.py`
- Android: `android/` · `io.github.avsessisozx.douyinindirici` · minSdk 29
  Gizli tarayıcı uygulamanın içinde (ayrı tarayıcı penceresi yok), galeriye kayıt,
  Paylaş → Douyin İndirici ile link alma
- Çeviriler: `ceviriler/tr.json`, `ceviriler/en.json` (her dil ayrı dosya; `tools/check_i18n.py`)
- Testler: `tests/`, `tools/run_tests.py`, ortak test verileri (`tools/make_vectors.py`),
  Android emülatör testi `tools/android_e2e.sh`
- CI: `build.yml` her dalda push'ta çalışır (EXE + APK)

## Yapılanlar
- Windows EXE kullanıcının PC'sinde çalışıyor (kullanıcı onayı)
- Android sürümü: 1.1.0 → 1.1.1 (yanlış doğrulama alarmı düzeltildi, Douyin ekranı gizlenebilir pencere)
- Gizlenmesi gereken öğeler iki sürümde de düzeltildi
- v1.2.0 (iki platformda): indirme geçmişi + "sadece yeniler" (profil sec_uid'ine bağlı, `history.py` / `History.kt`),
  duraklat/devam, "inemeyenleri tekrar dene", kopyalanabilir hata raporu (`report.py` / `Report.kt`),
  Douyin "Giriş yap" penceresini kapatma (`DISMISS_JS`), parçalı paralel indirme (Range; ayar: paralel sayı + parça sayısı),
  bulmaca çıkınca ses. Hesap/giriş ile ilgili özellikler bilerek yapılmadı (kullanıcı istemedi).
- Bilerek vazgeçilenler: "en iyi sunucu adresi" (URL sırası kalite/codec önceliğini taşıyor, sırayı bozunca galeri .webp indi);
  giriş penceresi için "dışına tıkla" yedeği (bağlantıya denk gelme riski) — yerine Esc denenir.

## Doğrulama durumu (v1.2.0)
- Doğrulandı: Windows birim + entegrasyon testleri (gerçek Chrome, sahte Douyin) geçti; Android derleme, birim testleri ve
  emülatör uçtan uca testi (sahte Douyin) GitHub'da geçti; giriş penceresi kapatıcı gerçek douyin.com'da (yerleşik tarayıcı) 2 kez denendi.
- Doğrulanmadı: Android'de gerçek telefon; geçmiş / duraklat / parçalı indirme gerçek Douyin CDN'inde; bulmaca sesi (sadece mantık testi);
  Android'de ses (ToneGenerator) ve SQLite geçmişi gerçek cihazda.

## Yarım / sıradaki
- v1.2.0 kullanıcının PC ve telefonunda denendiği henüz raporlanmadı (özellikle parçalı indirme ve geçmiş)
- Kullanıcı ileride geliştirmeye devam etmek istiyor (konu belirtilmedi)

## Bilinen sorunlar
- Kayıtlı açık hata yok.

## Kurallar
- Sürüm vermeden önce kendin test et; kullanıcıya bozuk sürüm test ettirme
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
