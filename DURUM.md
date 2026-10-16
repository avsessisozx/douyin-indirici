# DURUM — Douyin İndirici

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-10-16 · Son sürüm: **v1.1.1** (2026-10-16)

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

## Yarım / sıradaki
- Android sürümü kullanıcının telefonunda denendiği henüz raporlanmadı
- Kullanıcı ileride geliştirmeye devam etmek istiyor (konu belirtilmedi)

## Bilinen sorunlar
- Kayıtlı açık hata yok.

## Kurallar
- Sürüm vermeden önce kendin test et; kullanıcıya bozuk sürüm test ettirme
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
