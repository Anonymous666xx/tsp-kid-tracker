#!/bin/bash

set -e

echo "========================================="
echo "   Kid Tracker - Setup Script"
echo "========================================="
echo ""

# Check if wrangler is installed
if ! command -v npx &> /dev/null; then
    echo "[!] Node.js/npx not found. Please install Node.js first."
    echo "    https://nodejs.org/"
    exit 1
fi

echo "[1/4] Installing wrangler..."
cd worker
npm install

echo ""
echo "[2/4] Creating D1 database..."
npx wrangler d1 create kid-tracker-db

echo ""
echo "========================================="
echo "IMPORTANT: Copy the database_id from above"
echo "and paste it into wrangler.toml"
echo "========================================="
echo ""
read -p "Press Enter after you've updated wrangler.toml with your database_id..."

echo "[3/4] Initializing database..."
npx wrangler d1 execute kid-tracker-db --file=schema.sql

echo "[4/4] Deploying worker..."
npx wrangler deploy

echo ""
echo "========================================="
echo "   DEPLOYMENT COMPLETE!"
echo "========================================="
echo ""
echo "Your API URL will be shown above."
echo "Copy it and update the API_BASE_URL in the setup-apk.sh script."
echo ""
echo "Next steps:"
echo "1. Note your worker URL (looks like: https://kid-tracker.XXX.workers.dev)"
echo "2. Build the APK: cd ../kid-app && ./gradlew assembleRelease"
echo "3. Install the APK on kid's phone"
echo "4. Open the worker URL in parent's browser"
echo "5. Set the SAME 6-digit code on both"
echo ""
