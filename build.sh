#!/usr/bin/env bash
set -e

echo "Installing dependencies..."
pip install --upgrade pip
pip install -r requirements.txt

echo "Initializing database..."
python -c "from app import init_db; init_db()"

echo "Build completed successfully!"
