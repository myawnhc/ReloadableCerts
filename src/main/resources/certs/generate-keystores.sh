#!/usr/bin/env bash
set -euo pipefail

########################################
# Configuration
########################################

PASSWORD="changeit"
VALIDITY_DAYS=365
KEYALG="RSA"
KEYSIZE=2048

# Output directory (keeps things tidy)
OUTDIR="./tls-material"

########################################
# Helpers
########################################

gen_generation () {
  local GEN="$1"
  local ALIAS="$2"
  local CN="$3"

  local KS_JKS="${OUTDIR}/keystore-${GEN}.jks"
  local KS_P12="${OUTDIR}/keystore-${GEN}.p12"
  local TS_JKS="${OUTDIR}/truststore-${GEN}.jks"
  local TS_P12="${OUTDIR}/truststore-${GEN}.p12"
  local CERT_PEM="${OUTDIR}/cert-${GEN}.pem"

  echo "=== Generating ${GEN} ==="

  # 1. Keystore (JKS)
  keytool -genkeypair \
    -alias "${ALIAS}" \
    -keyalg "${KEYALG}" \
    -keysize "${KEYSIZE}" \
    -validity "${VALIDITY_DAYS}" \
    -dname "CN=${CN}, OU=Test, O=Example, L=Test, ST=Test, C=US" \
    -keystore "${KS_JKS}" \
    -storetype JKS \
    -storepass "${PASSWORD}" \
    -keypass "${PASSWORD}"

  # 2. Export public cert
  keytool -exportcert \
    -alias "${ALIAS}" \
    -keystore "${KS_JKS}" \
    -storepass "${PASSWORD}" \
    -rfc \
    -file "${CERT_PEM}"

  # 3. Truststore (JKS)
  keytool -importcert \
    -alias "${ALIAS}" \
    -file "${CERT_PEM}" \
    -keystore "${TS_JKS}" \
    -storetype JKS \
    -storepass "${PASSWORD}" \
    -noprompt

  # 4. Convert keystore to PKCS12
  keytool -importkeystore \
    -srckeystore "${KS_JKS}" \
    -srcstoretype JKS \
    -srcstorepass "${PASSWORD}" \
    -destkeystore "${KS_P12}" \
    -deststoretype PKCS12 \
    -deststorepass "${PASSWORD}"

  # 5. Convert truststore to PKCS12
  keytool -importkeystore \
    -srckeystore "${TS_JKS}" \
    -srcstoretype JKS \
    -srcstorepass "${PASSWORD}" \
    -destkeystore "${TS_P12}" \
    -deststoretype PKCS12 \
    -deststorepass "${PASSWORD}"

  echo
}

########################################
# Main
########################################

echo "Cleaning output directory..."
rm -rf "${OUTDIR}"
mkdir -p "${OUTDIR}"

gen_generation "v1" "testkey-v1" "localhost"
gen_generation "v2" "testkey-v2" "localhost-rotated"

echo "All keystores and truststores generated in ${OUTDIR}"

