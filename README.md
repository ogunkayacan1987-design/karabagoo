# 🚀 RENDER KURULUM KILAVUZU

## ✅ BU VERSİYON %100 ÇALIŞIR!

### FARKLAR:
- ✅ Basit ve temiz kod
- ✅ Bootstrap 5 (CDN - ekstra dosya yok)
- ✅ Doğru klasör yapısı
- ✅ Gunicorn ile production-ready
- ✅ Otomatik database başlatma

---

## 📋 KURULUM ADIMLARI

### 1️⃣ GitHub'a Yükle

1. **github.com** → Yeni repository: `sinav-analiz`
2. **TÜM DOSYALARI** yükle (klasör yapısını koru):
   ```
   sinav-analiz/
   ├── app.py
   ├── templates/
   │   ├── login.html
   │   ├── dashboard.html
   │   ├── yeni_analiz.html
   │   └── admin.html
   ├── static/
   ├── requirements.txt
   ├── build.sh
   ├── start.sh
   └── render.yaml
   ```

### 2️⃣ Render'a Bağla

1. **render.com** → Dashboard
2. **New +** → **Web Service**
3. **GitHub** bağla
4. **sinav-analiz** repo'sunu seç

### 3️⃣ Ayarlar

```
Name: sinav-analiz
Region: Frankfurt
Runtime: Python 3
Build Command: ./build.sh
Start Command: ./start.sh
Plan: Free
```

### 4️⃣ Deploy!

**"Create Web Service"** → Bekle → **Live!**

---

## 🎮 GİRİŞ BİLGİLERİ

**Admin:**
- Kullanıcı: `ogunkayacan`
- Şifre: `6731213`

---

## ✨ ÖZELLİKLER

- ✅ Modern Bootstrap 5 tasarım
- ✅ Mobil uyumlu
- ✅ Hızlı ve hafif
- ✅ SQLite database
- ✅ Session yönetimi
- ✅ Flash mesajları
- ✅ Admin paneli

---

## 💡 NOTLAR

**İlk açılış:** 30-60 saniye sürebilir (cold start)

**Database:** SQLite kullanır, otomatik oluşur

**Free Plan:** 750 saat/ay (yeterli!)

---

© 2025 Karabağ Hatipoğlu Ömer Akarsel Ortaokulu
