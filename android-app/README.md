# PDF Soru Çıkarıcı (PDF Question Extractor)

PDF dosyalarından soruları otomatik olarak tespit edip JPEG olarak kaydeden Android uygulaması.

## Özellikler

- **PDF Açma**: Cihazdan PDF dosyası seçme
- **Yapay Zeka ile Soru Tespiti**: ML Kit text recognition kullanarak soruların otomatik tespiti
- **Soru Önizleme**: Tespit edilen soruları önizleme
- **JPEG Kaydetme**: Soruları yüksek kalitede JPEG olarak galeriye kaydetme
- **Toplu İndirme**: Tüm soruları veya seçilenleri tek seferde indirme
- **Paylaşma**: Soruları diğer uygulamalarla paylaşma

## Teknik Detaylar

### Kullanılan Teknolojiler

- **Kotlin**: Modern Android geliştirme dili
- **Android PDF Renderer**: PDF sayfalarını bitmap'e dönüştürme
- **ML Kit Text Recognition**: Metin tanıma ve soru tespiti
- **Coroutines**: Asenkron işlemler
- **ViewModel + StateFlow**: MVVM mimari pattern
- **Material Design 3**: Modern UI bileşenleri

### Minimum Gereksinimler

- Android 7.0 (API 24) ve üzeri
- ~50MB depolama alanı

## Kurulum

### Android Studio ile

1. Projeyi Android Studio'da açın
2. Gradle sync yapın
3. Cihaz/emülatör seçin
4. Run butonuna tıklayın

### APK Oluşturma

```bash
cd android-app
./gradlew assembleRelease
```

APK dosyası `app/build/outputs/apk/release/` klasöründe oluşturulacaktır.

## Kullanım

1. **PDF Seçimi**: "PDF Seç" butonuna tıklayın ve bir PDF dosyası seçin
2. **Soru Tespiti**: "Soruları Tespit Et" butonuna tıklayın
3. **Seçim**: İndirmek istediğiniz soruları seçin (varsayılan olarak hepsi seçili)
4. **Kaydetme**: "Tümünü Kaydet" veya "Seçilenleri Kaydet" butonlarını kullanın
5. **Paylaşma**: Her sorunun altındaki paylaş butonuyla paylaşabilirsiniz

## Proje Yapısı

```
android-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/karabagoo/pdfquestionextractor/
│   │   │   ├── data/          # Veri modelleri
│   │   │   ├── ml/            # ML Kit entegrasyonu
│   │   │   ├── ui/            # Activity ve Adapter'lar
│   │   │   └── util/          # Yardımcı sınıflar
│   │   └── res/               # Kaynaklar (layout, drawable, values)
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## Soru Tespit Algoritması

Uygulama şu pattern'ları kullanarak soruları tespit eder:

- `1.` veya `1)` formatında numaralı sorular
- `Soru 1`, `S.1`, `S 1` formatları
- `A.`, `B.`, `C.`, `D.` formatındaki şıklar

Her tespit edilen soru numarasından bir sonraki soruya kadar olan alan kesilir ve JPEG olarak kaydedilir.

## Lisans

Bu proje MIT lisansı altında sunulmaktadır.
