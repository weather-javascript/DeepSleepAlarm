#!/usr/bin/env bash
##############################################################################
#  setup_keystore.sh
#
#  目的:
#    初回リリース前に署名用キーストアを生成し、
#    GitHub Secrets に登録する Base64 文字列を出力するスクリプト。
#
#  使い方:
#    chmod +x scripts/setup_keystore.sh
#    ./scripts/setup_keystore.sh
#
#  実行すると:
#    1. release.jks（キーストアファイル）を生成
#    2. keystore.properties（ローカル開発用設定）を生成
#    3. GitHub Secrets に貼り付ける Base64 文字列を表示
#
#  注意:
#    - release.jks と keystore.properties は .gitignore に含まれています。
#      絶対にリポジトリにコミットしないでください。
#    - 生成したキーストアは安全な場所に必ずバックアップしてください。
#      紛失するとアプリの更新ができなくなります。
##############################################################################

set -euo pipefail

# ── カラー定義 ────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  熟睡アラーム — キーストア生成セットアップ          ${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo ""

# ── 入力受付 ─────────────────────────────────────────────────────────────────
read -rp "$(echo -e "${YELLOW}キーのエイリアス名 [deepsleep-key]: ${NC}")" KEY_ALIAS
KEY_ALIAS="${KEY_ALIAS:-deepsleep-key}"

read -rsp "$(echo -e "${YELLOW}キーストアのパスワード（8文字以上）: ${NC}")" STORE_PASS
echo ""
read -rsp "$(echo -e "${YELLOW}キーのパスワード（8文字以上）: ${NC}")" KEY_PASS
echo ""

read -rp "$(echo -e "${YELLOW}氏名または組織名 [Deep Sleep Alarm]: ${NC}")" DNAME_CN
DNAME_CN="${DNAME_CN:-Deep Sleep Alarm}"

read -rp "$(echo -e "${YELLOW}有効期限（年数）[25]: ${NC}")" VALIDITY
VALIDITY="${VALIDITY:-25}"

KEYSTORE_PATH="$(dirname "$0")/../release.jks"
PROPS_PATH="$(dirname "$0")/../keystore.properties"

# ── 既存ファイルの確認 ────────────────────────────────────────────────────────
if [ -f "$KEYSTORE_PATH" ]; then
    echo -e "${RED}⚠️  release.jks が既に存在します。上書きしますか？ [y/N]: ${NC}"
    read -r OVERWRITE
    if [[ ! "$OVERWRITE" =~ ^[Yy]$ ]]; then
        echo "キャンセルしました。"
        exit 0
    fi
fi

# ── keytool コマンドの確認 ────────────────────────────────────────────────────
if ! command -v keytool &> /dev/null; then
    echo -e "${RED}エラー: keytool が見つかりません。JDK をインストールしてください。${NC}"
    echo "  macOS:  brew install openjdk"
    echo "  Ubuntu: sudo apt-get install default-jdk"
    exit 1
fi

# ── キーストア生成 ─────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}🔑 キーストアを生成しています...${NC}"

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_PATH" \
    -storetype JKS \
    -keyalg RSA \
    -keysize 2048 \
    -validity $((VALIDITY * 365)) \
    -alias "$KEY_ALIAS" \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=$DNAME_CN, OU=Android, O=$DNAME_CN, L=Tokyo, S=Tokyo, C=JP"

echo -e "${GREEN}✅ release.jks を生成しました${NC}"

# ── keystore.properties 生成 ──────────────────────────────────────────────────
cat > "$PROPS_PATH" << EOF
# ローカル開発用の署名設定
# このファイルは .gitignore に含まれています。絶対にコミットしないでください。
storeFile=../release.jks
storePassword=${STORE_PASS}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASS}
EOF

echo -e "${GREEN}✅ keystore.properties を生成しました${NC}"

# ── Base64 エンコード ─────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  GitHub Secrets に登録する値                      ${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}以下の値を GitHub の Settings → Secrets → Actions に登録してください。${NC}"
echo ""

# macOS と Linux でコマンドが異なる
if [[ "$OSTYPE" == "darwin"* ]]; then
    BASE64_CMD="base64 -i"
else
    BASE64_CMD="base64 -w 0"
fi

KEYSTORE_BASE64=$($BASE64_CMD "$KEYSTORE_PATH")

echo -e "${GREEN}Secret 名: KEYSTORE_BASE64${NC}"
echo "値（全てコピーしてください）:"
echo "─────────────────────────────────"
echo "$KEYSTORE_BASE64"
echo "─────────────────────────────────"
echo ""

echo -e "${GREEN}Secret 名: KEYSTORE_PASSWORD${NC}"
echo "値: ${STORE_PASS}"
echo ""

echo -e "${GREEN}Secret 名: KEY_ALIAS${NC}"
echo "値: ${KEY_ALIAS}"
echo ""

echo -e "${GREEN}Secret 名: KEY_PASSWORD${NC}"
echo "値: ${KEY_PASS}"
echo ""

echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo -e "${RED}⚠️  重要: release.jks を安全な場所にバックアップしてください！${NC}"
echo -e "${RED}   このファイルを紛失するとアプリを更新できなくなります。${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"

# ── リリース手順の表示 ────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}リリース手順:${NC}"
echo "  1. 上記の Secrets を GitHub に登録"
echo "  2. git tag v1.0.0"
echo "  3. git push origin v1.0.0"
echo "  → GitHub Actions が自動ビルドし Releases に APK を公開します"
