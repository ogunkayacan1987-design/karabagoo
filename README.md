# SpeedGuard - Minimal Sürüş Destek Uygulaması

Bu proje, **Flutter** ve **GitHub Actions** kullanılarak geliştirilmiş, otomatik APK derleme özelliğine sahip minimal bir Android uygulamasıdır.

Yerel bilgisayarınızda Flutter kurulu olmasa bile, GitHub'a yüklediğinizde otomatik olarak APK oluşturulur.

## 🚀 Kurulum ve APK Oluşturma Adımları

Aşağıdaki adımları sırasıyla uygulayarak projeyi GitHub'a yükleyin ve ilk APK'nızı alın.

### 1. Terminali Açın ve Klasöre Gidin
Eğer proje klasöründe değilseniz:
```bash
cd speed_guard
```

### 2. Git Deposunu Başlatın
```bash
git init
git branch -M main
git add .
git commit -m "SpeedGuard v1.0 - Ilk kurulum"
```

### 3. GitHub Deposuna Bağlayın ve Yükleyin
Önce GitHub panelinizden **yeni bir boş depo (repository)** oluşturun (Adı örneğin: `speed_guard`).
Ardından terminale dönüp (REPO_URL kısmını kendi deponuzun adresiyle değiştirin):

```bash
git remote add origin https://github.com/KULLANICI_ADINIZ/speed_guard.git
git push -u origin main
```

### 4. APK'yı İndirin
1.  GitHub deposu sayfanızda **Actions** sekmesine tıklayın.
2.  **Build Android App** iş akışının çalışmaya başladığını göreceksiniz.
3.  İşlem tamamlandığında (yaklaşık 3-5 dakika), işlemin içine girin.
4.  En altta **Artifacts** kısmında `app-release` dosyasını indirin.
5.  İçindeki `.apk` dosyasını telefonunuza atıp yükleyin.

## 📱 Özellikler
- **GPS Hızı:** Anlık hızınızı büyük puntolarla gösterir.
- **Hız Sınırı:** OpenStreetMap (Overpass API) üzerinden bulunduğunuz yolun hız sınırını çeker.
- **Akıllı Uyarı:** Hız sınırını aşarsanız ekran **KIRMIZI** olur ve sesli olarak "Hız sınırı aşıldı" uyarısı verir.
- **Cloud-Native:** `flutter create` komutu sunucuda çalışır, yerel kurulum gerektirmez.

## ⚠️ Notlar
- İlk açılışta **Konum İzni** vermeniz gerekmektedir.
- Hız sınırı verisi internet bağlantısı gerektirir (OpenStreetMap veritabanından çekilir).
- Hız sınırı bulunamazsa varsayılan olarak **50 km/s** kabul edilir.
