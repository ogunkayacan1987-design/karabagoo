from flask import Flask, render_template, request, redirect, url_for, session, flash
from werkzeug.security import generate_password_hash, check_password_hash
import sqlite3
from datetime import datetime
import os
from functools import wraps

app = Flask(__name__)
app.secret_key = os.environ.get('SECRET_KEY', 'karabag-hatipoglu-2025-secret-key')

# Database helper
def get_db():
    conn = sqlite3.connect('sinav_analiz.db')
    conn.row_factory = sqlite3.Row
    return conn

# Initialize database
def init_db():
    conn = get_db()
    cursor = conn.cursor()
    
    # Users table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password TEXT NOT NULL,
            full_name TEXT NOT NULL,
            class TEXT NOT NULL,
            role TEXT DEFAULT 'student',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
    # Analizler table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS analizler (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            sinav_tarihi TEXT,
            sinav_adi TEXT,
            basarili_konular TEXT,
            zayif_konular TEXT,
            hedef_toplam INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users (id)
        )
    ''')
    
    # Add default admin user
    try:
        hashed = generate_password_hash('6731213')
        cursor.execute('''
            INSERT INTO users (username, password, full_name, class, role)
            VALUES (?, ?, ?, ?, ?)
        ''', ('ogunkayacan', hashed, 'Ogün Kayacan', 'Müdür', 'admin'))
        conn.commit()
    except sqlite3.IntegrityError:
        pass
    
    conn.close()

# Login required decorator
def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_id' not in session:
            flash('Lütfen giriş yapın', 'warning')
            return redirect(url_for('login'))
        return f(*args, **kwargs)
    return decorated_function

# Routes
@app.route('/')
def index():
    if 'user_id' in session:
        return redirect(url_for('dashboard'))
    return redirect(url_for('login'))

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        username = request.form.get('username')
        password = request.form.get('password')
        
        conn = get_db()
        user = conn.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()
        conn.close()
        
        if user and check_password_hash(user['password'], password):
            session['user_id'] = user['id']
            session['username'] = user['username']
            session['full_name'] = user['full_name']
            session['role'] = user['role']
            flash(f'Hoş geldiniz {user["full_name"]}!', 'success')
            return redirect(url_for('dashboard'))
        else:
            flash('Kullanıcı adı veya şifre hatalı', 'danger')
    
    return render_template('login.html')

@app.route('/logout')
def logout():
    session.clear()
    flash('Çıkış yapıldı', 'info')
    return redirect(url_for('login'))

@app.route('/dashboard')
@login_required
def dashboard():
    conn = get_db()
    analizler = conn.execute('''
        SELECT * FROM analizler 
        WHERE user_id = ? 
        ORDER BY created_at DESC
    ''', (session['user_id'],)).fetchall()
    conn.close()
    
    return render_template('dashboard.html', analizler=analizler)

@app.route('/yeni-analiz', methods=['GET', 'POST'])
@login_required
def yeni_analiz():
    if request.method == 'POST':
        conn = get_db()
        conn.execute('''
            INSERT INTO analizler (
                user_id, sinav_tarihi, sinav_adi, 
                basarili_konular, zayif_konular, hedef_toplam
            ) VALUES (?, ?, ?, ?, ?, ?)
        ''', (
            session['user_id'],
            request.form.get('sinav_tarihi'),
            request.form.get('sinav_adi'),
            request.form.get('basarili_konular'),
            request.form.get('zayif_konular'),
            request.form.get('hedef_toplam')
        ))
        conn.commit()
        conn.close()
        
        flash('Analiz kaydedildi!', 'success')
        return redirect(url_for('dashboard'))
    
    return render_template('yeni_analiz.html')

@app.route('/admin')
@login_required
def admin_panel():
    if session.get('role') != 'admin':
        flash('Yetkiniz yok', 'danger')
        return redirect(url_for('dashboard'))
    
    conn = get_db()
    ogrenciler = conn.execute('SELECT * FROM users WHERE role = "student"').fetchall()
    conn.close()
    
    return render_template('admin.html', ogrenciler=ogrenciler)

# Initialize DB on startup
if not os.path.exists('sinav_analiz.db'):
    init_db()

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
