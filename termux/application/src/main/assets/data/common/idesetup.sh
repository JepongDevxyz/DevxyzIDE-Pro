#!/bin/bash

set -eu

Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

yes='^[Yy][Ee]?[Ss]?$'

# Defualt values
arch=$(uname -m)
install_dir=$HOME
sdkver_org=34.0.4
with_cmdline=true
assume_yes=true
manifest="https://raw.githubusercontent.com/AndroidIDEOfficial/androidide-tools/main/manifest.json"
pkgm="pkg"
pkg_curl="libcurl"
pkgs="jq tar"
jdk_version="17"

print_info() {
  # shellcheck disable=SC2059
  printf "${Blue}$1$Color_Off\n"
}

print_err() {
  # shellcheck disable=SC2059
  printf "${Red}$1$Color_Off\n"
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}$1$Color_Off\n"
}

print_success() {
  # shellcheck disable=SC2059
  printf "${Green}$1$Color_Off\n"
}

is_yes() {

  msg=$1

  printf "%s ([y]es/[n]o): " "$msg"

  if [ "$assume_yes" == "true" ]; then
    ans="y"
    echo $ans
  else
    read -r ans
  fi

  if [[ "$ans" =~ $yes ]]; then
    return 0
  fi

  return 1
}

check_arg_value() {
  option_name="$1"
  arg_value="$2"
  if [[ -z "$arg_value" ]]; then
    print_err "No value provided for $option_name!" >&2
    exit 1
  fi
}

check_command_exists() {
  if command -v "$1" &>/dev/null; then
    return
  else
    print_err "Command '$1' not found!"
    exit 1
  fi
}

# shellcheck disable=SC2068
install_packages() {
  if [ "$assume_yes" == "true" ]; then
    $pkgm install $@ -y
  else
    $pkgm install $@
  fi
}

print_help() {
  echo "AndroidIDE build tools installer"
  echo "This script helps you easily install build tools in AndroidIDE."
  echo ""
  echo "Usage:"
  echo "${0} -s 34.0.4 -c -j 17"
  echo "This will install Android SDK 34.0.4 with command line tools and JDK 17."
  echo ""
  echo "Options :"
  echo "-i   Set the installation directory. Defaults to \$HOME."
  echo "-s   Android SDK version to download."
  echo "-c   Download Android SDK with command line tools."
  echo "-m   Manifest file URL. Defaults to 'manifest.json' in 'androidide-tools' GitHub repository."
  echo "-j   OpenJDK version to install. Values can be '17' or '21'"
  echo "-g   Install package: 'git'."
  echo "-o   Install package: 'git'."
  echo "-y   Assume \"yes\" as answer to all prompts and run non-interactively."
  echo ""
  echo "For testing purposes:"
  echo "-a   CPU architecture. Extracted using 'uname -m' by default."
  echo "-p   Package manager. Defaults to 'pkg'."
  echo "-l   Name of curl package that will be installed before starting installation process. Defaults to 'libcurl'."
  echo ""
  echo "-h   Prints this message."
}

download_and_extract() {
  name=$1
  url=$2
  dir=$3
  dest=$4
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
  fi
  cd "$dir"
  do_download=true
  if [ -f "$dest" ]; then
    name=$(basename "$dest")
    print_info "File ${name} already exists."
    if is_yes "Do you want to skip the download process?"; then
      do_download=false
    fi
    echo ""
  fi
  if [ "$do_download" = "true" ]; then
    print_info "Downloading $name..."
    curl -L -o "$dest" "$url" --http1.1
    print_success "$name has been downloaded."
    echo ""
  fi
  if [ ! -f "$dest" ]; then
    print_err "The downloaded file $name does not exist. Cannot proceed..."
    exit 1
  fi
  print_info "Extracting downloaded archive..."
  tar xvJf "$dest" && print_info "Extracted successfully"
  echo ""
  rm -vf "$dest"
  cd -
}

download_comp() {
  nm=$1
  jq_query=$2
  mdir=$3
  dname=$4
  print_info "Extracting URL for $nm from manifest..."
  url=$(jq -r "${jq_query}" "$downloaded_manifest")
  print_success "Found URL: $url"
  echo ""
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz"
}

# pkg_usable <name>: 0 if the package's files/commands appear present and usable.
# Unlike dpkg -s, this does not trust dpkg status, which returns success for
# packages that are merely known but not installed, and says nothing about
# packages installed by extraction (which dpkg never registers).
pkg_usable() {
  case "$1" in
    openjdk-17|openjdk-21)
      [ -x "${SYSROOT:-$PREFIX}/opt/openjdk/bin/java" ]
      ;;
    libcurl)
      ls "$PREFIX/lib"/libcurl.so* >/dev/null 2>&1
      ;;
    openssh)
      command -v ssh >/dev/null 2>&1
      ;;
    *)
      command -v "$1" >/dev/null 2>&1
      ;;
  esac
}

# install_deb_extract <deb_path> <deb_file>: downloads a .deb via curl (if not
# already present), extracts it with dpkg-deb -x, and copies its contents into
# $PREFIX, translating the old com.itsaky.androidide prefix to the DevxyzIDE
# prefix. This bypasses dpkg -i, which fails with Permission denied because
# the .debs contain hardcoded com.itsaky.androidide paths.
install_deb_extract() {
  deb_path="$1"
  deb_file="$2"
  if ! download_deb "$deb_path" "$deb_file"; then
    return 1
  fi
  deb_extract_dir="${deb_file%.deb}_extract"
  rm -rf "$deb_extract_dir"
  if ! dpkg-deb -x "$deb_file" "$deb_extract_dir"; then
    print_err "Failed to extract $deb_file."
    return 1
  fi
  itsaky_root="$deb_extract_dir/data/data/com.itsaky.androidide/files/usr"
  if [ -d "$itsaky_root" ]; then
    cp -a "$itsaky_root/." "$PREFIX/"
  else
    cp -a "$deb_extract_dir/." "$PREFIX/"
  fi
  print_success "Installed $deb_file via extraction."
  return 0
}

# download_deb downloads a .deb without extracting it. The output file is
# named explicitly (-o) instead of -O: -O derives the local name from the
# final URL, which produced "Failed to open the file ... No such file or
# directory" + curl (23) write errors on device (krb5, 2026-10-30). Each
# download is verified non-empty and retried up to 3 times.
download_deb() {
  deb_path="$1"
  deb_file="$2"
  if [ -f "$deb_file" ] && [ -s "$deb_file" ]; then
    return 0
  fi
  rm -f "$deb_file"
  print_info "Downloading $deb_file..."
  deb_try=1
  while [ "$deb_try" -le 3 ]; do
    if curl -fL -o "$deb_file" "https://packages.androidide.com/apt/termux-main/$deb_path" && [ -s "$deb_file" ]; then
      return 0
    fi
    rm -f "$deb_file"
    if [ "$deb_try" -lt 3 ]; then
      print_warn "Download of $deb_file failed (attempt $deb_try of 3). Retrying..."
      sleep 2
    fi
    deb_try=$((deb_try + 1))
  done
  print_err "Failed to download $deb_file via curl."
  return 1
}
# finalize_jdk: renames a versioned JDK dir (e.g. opt/openjdk-17.0) to the
# unversioned path the IDE expects ($SYSROOT/opt/openjdk), copies libz into
# the JDK lib dir (the Android linker does not search $PREFIX/lib on its own),
# and returns 0 iff the JDK binary is executable.
finalize_jdk() {
  jdk_target="${SYSROOT:-$PREFIX}/opt/openjdk"
  if [ ! -x "$jdk_target/bin/java" ]; then
    for jdk_candidate in "$PREFIX/opt/openjdk-17.0" "$PREFIX/opt/openjdk-17" "$PREFIX/opt/openjdk-$jdk_version"; do
      if [ -x "$jdk_candidate/bin/java" ]; then
        mkdir -p "$PREFIX/opt"
        rm -rf "$jdk_target"
        cp -a "$jdk_candidate" "$jdk_target"
        break
      fi
    done
  fi
  if [ -e "$PREFIX/lib/libz.so.1" ] || [ -e "$PREFIX/lib/libz.so.1.3" ]; then
    cp -a "$PREFIX/lib"/libz.so.1* "$jdk_target/lib/" 2>/dev/null || true
  fi
  [ -x "$jdk_target/bin/java" ]
}

# install_jdk_extract: installs OpenJDK 17 + zlib via direct .deb extraction
# (bypasses apt and the dpkg -i prefix problem), finalizes the JDK layout,
# and returns 0 iff the JDK binary is executable. Only 17 is available via
# this path; 21 must come from apt.
install_jdk_extract() {
  jdk_work_dir="$PREFIX/tmp/jdkdl"
  mkdir -p "$jdk_work_dir"
  jdk_work_start="$(pwd)"
  cd "$jdk_work_dir" || return 1
  if ! install_deb_extract "dists/stable/main/binary-aarch64/openjdk-17_17.0-30_aarch64.deb" "openjdk-17_17.0-30_aarch64.deb"; then
    cd "$jdk_work_start" || true
    return 1
  fi
  if ! install_deb_extract "dists/stable/main/binary-aarch64/zlib_1.3-3_aarch64.deb" "zlib_1.3-3_aarch64.deb"; then
    cd "$jdk_work_start" || true
    return 1
  fi
  cd "$jdk_work_start" || true
  finalize_jdk
}

while [ $# -gt 0 ]; do
  case $1 in
  -c | --with-cmdline-tools)
    shift
    with_cmdline=false
    ;;
  -g | --with-git)
    shift
    pkgs+=" git"
    ;;
  -o | --with-openssh)
    shift
    pkgs+=" openssh"
    ;;
  -y | --assume-yes)
    shift
    assume_yes=true
  
    ;;
    --no-assume-yes)
    shift
    assume_yes=false
    ;;
  -i | --install-dir)
    shift
    check_arg_value "--install-dir" "${1:-}"
    install_dir="$1"
    ;;
  -m | --manifest)
    shift
    check_arg_value "--manifest" "${1:-}"
    manifest="$1"
    ;;
  -s | --sdk)
    shift
    check_arg_value "--sdk" "${1:-}"
    sdkver_org="$1"
    ;;
  -j | --jdk)
    shift
    check_arg_value "--jdk" "${1:-}"
    jdk_version="$1"
    ;;
  -a | --arch)
    shift
    check_arg_value "--arch" "${1:-}"
    arch="$1"
    ;;
  -p | --package-manager)
    shift
    check_arg_value "--package-manager" "${1:-}"
    pkgm="$1"
    ;;
  -l | --curl)
    shift
    check_arg_value "--curl" "${1:-}"
    pkg_curl="$1"
    ;;
  -h | --help)
    print_help
    exit 0
    ;;
  -*)
    echo "Invalid option: $1" >&2
    exit 1
    ;;
  *) break ;;
  esac
  shift
done

if [ "$arch" = "armv7l" ]; then
  arch="arm"
fi
if [ "$arch" = "armv8l" ]; then
  arch="arm"
fi

check_command_exists "$pkgm"

if [ "$jdk_version" == "21" ]; then
  print_warn "OpenJDK 21 support in AndroidIDE is experimental. It may or may not work properly."
  print_warn "Also, OpenJDK 21 is only supported in Gradle v8.4 and newer. Older versions of Gradle will NOT work!"
  if ! is_yes "Do you still want to install OpenJDK 21?"; then
    jdk_version="17"
    print_info "OpenJDK version has been reset to '17'"
  fi
fi

if [ "$jdk_version" != "17" ] && [ "$jdk_version" != "21" ]; then
  print_err "Invalid JDK version '$jdk_version'. Value can be '17' or '21'."
  exit 1
fi

sdk_version="_${sdkver_org//'.'/'_'}"

pkgs+=" $pkg_curl"

echo "------------------------------------------"
echo "Installation directory    : ${install_dir}"
echo "SDK version               : ${sdkver_org}"
echo "JDK version               : ${jdk_version}"
echo "With command line tools   : ${with_cmdline}"
echo "Extra packages            : ${pkgs}"
echo "CPU architecture          : ${arch}"
echo "------------------------------------------"

if ! is_yes "Confirm configuration"; then
  print_err "Aborting..."
  exit 1
fi

if [ ! -f "$install_dir" ]; then
  print_info "Installation directory does not exist. Creating directory..."
  mkdir -p "$install_dir"
fi

if [ ! command -v "$pkgm" ] &>/dev/null; then
  print_err "'$pkgm' command not found. Try installing 'termux-tools' and 'apt'."
  exit 1
fi

# Update repositories and packages
print_info "Update packages..."

# DevxyzIDE baked-in apt safety net
PREFIX="${PREFIX:-/data/data/com.jepongdevxyz.pro/files/usr}"
mkdir -p "$PREFIX/var/lib/apt/lists" "$PREFIX/etc/apt"
if [ ! -e "$PREFIX/etc/aptt" ]; then
  ln -s "$PREFIX/etc/apt" "$PREFIX/etc/aptt" || true
fi
apt_update_ok=true
if ! "$pkgm" update; then
  if ! apt-get update -o Debug::NoLocking=true; then
    apt_update_ok=false
  fi
fi
if [ "$apt_update_ok" = "true" ]; then
  if [ "$assume_yes" == "true" ]; then
    $pkgm upgrade -y || apt_update_ok=false
  else
    $pkgm upgrade || apt_update_ok=false
  fi
  if [ "$apt_update_ok" = "true" ]; then
    print_info "Installing required packages.."
    if install_packages $pkgs; then
      print_success "Packages installed"
    else
      apt_update_ok=false
    fi
  fi
  echo ""
fi
if [ "$apt_update_ok" = "false" ]; then
  print_warn "WARNING: apt update failed with 400 Bad Request from packages.androidide.com. Setup will continue via curl+extract bypass."
  print_warn "apt update failed, so required packages will be installed from .deb files downloaded with curl instead of apt."
  apt_bypass_needed=false
  for apt_bypass_pkg in jq tar git openssh libcurl; do
    if ! pkg_usable "$apt_bypass_pkg"; then
      apt_bypass_needed=true
    fi
  done
  if [ ! -x "${SYSROOT:-$PREFIX}/opt/openjdk/bin/java" ]; then
    apt_bypass_needed=true
  fi
  if [ "$apt_bypass_needed" = "true" ]; then
    apt_bypass_dir="$PREFIX/tmp/aptbypass"
    apt_bypass_start_dir="$(pwd)"
    rm -rf "$apt_bypass_dir"
    mkdir -p "$apt_bypass_dir"
    cd "$apt_bypass_dir"
    apt_bypass_debs="git_2.43.0_aarch64.deb jq_1.7_aarch64.deb krb5_1.21.2_aarch64.deb ldns_1.8.3-2_aarch64.deb less_633-1_aarch64.deb libandroid-glob_0.6-2_aarch64.deb libandroid-support_28-3_aarch64.deb libc++_26b_aarch64.deb libcurl_8.5.0_aarch64.deb libdb_18.1.40-4_aarch64.deb libedit_20221030-3.1-0_aarch64.deb libiconv_1.17_aarch64.deb libjpeg-turbo_3.0.1_aarch64.deb libnghttp2_1.58.0_aarch64.deb libresolv-wrapper_1.1.7-4_aarch64.deb libssh2_1.11.0_aarch64.deb ncurses_6.4.20231001-3_aarch64.deb oniguruma_6.9.9_aarch64.deb openjdk-17_17.0-30_aarch64.deb openssh_9.5p1-2_aarch64.deb openssh-sftp-server_9.5p1-2_aarch64.deb openssl_1:3.1.4_aarch64.deb pcre2_10.42_aarch64.deb readline_8.2.1_aarch64.deb resolv-conf_1.3_aarch64.deb tar_1.35_aarch64.deb termux-auth_1.4-2_aarch64.deb zlib_1.3-3_aarch64.deb"
    for deb_name in $apt_bypass_debs; do
      mkdir -p "$apt_bypass_dir"
      cd "$apt_bypass_dir" || exit 1
      if ! download_deb "dists/stable/main/binary-aarch64/$deb_name" "$deb_name"; then
        print_err "Aborting curl+extract bypass."
        exit 1
      fi
    done
    # Deb binaries carry the old com.itsaky.androidide RUNPATH, so make dpkg-deb and tar find libraries in the IDE prefix lib. LD_LIBRARY_PATH takes precedence over RUNPATH.
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
# Phase 2: verify every .deb is present, then extract them all.
    for deb_name in $apt_bypass_debs; do
    mkdir -p "$apt_bypass_dir"
    cd "$apt_bypass_dir" || exit 1
    if ! download_deb "dists/stable/main/binary-aarch64/$deb_name" "$deb_name"; then
    print_err "Aborting curl+extract bypass."
    exit 1
    fi
    if ! install_deb_extract "dists/stable/main/binary-aarch64/$deb_name" "$deb_name"; then
    print_err "Aborting curl+extract bypass."
    exit 1
    fi
    done
    cd "$apt_bypass_start_dir"
    if finalize_jdk; then
      print_success "curl+extract bypass installed all required packages."
    else
    
      print_err "JDK binary still missing after curl+extract bypass. Aborting."
      exit 1
    fi
    rm -rf "$apt_bypass_dir" || true
  else
    print_info "All required packages are already installed. Skipping curl+extract bypass download."
  fi
  echo ""
fi

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
curl -L -o "$downloaded_manifest" "$manifest" && print_success "Manifest file downloaded"
echo ""

# Install the Android SDK
download_comp "Android SDK" ".android_sdk" "$install_dir" "android-sdk"

# Install build tools
download_comp "Android SDK Build Tools" ".build_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-build-tools"

# Install platform tools
download_comp "Android SDK Platform Tools" ".platform_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-platform-tools"

if [ "$with_cmdline" = true ]; then
  download_comp "Command-line tools" ".cmdline_tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
# The JDK is usable iff its binary exists and runs; dpkg status is NOT
# trusted here (it reports success for known-but-not-installed packages).
print_info "Installing package: 'openjdk-$jdk_version'"
jdk_bin="${SYSROOT:-$PREFIX}/opt/openjdk/bin/java"
if [ -x "$jdk_bin" ]; then
  print_info "JDK $jdk_version is already installed."
else
  if [ "$apt_update_ok" = "true" ]; then
    install_packages "openjdk-$jdk_version" || true
  fi
  if [ ! -x "$jdk_bin" ]; then
    if [ "$jdk_version" = "17" ]; then
      print_info "JDK binary not found. Installing OpenJDK 17 via direct .deb extraction..."
      if ! install_jdk_extract; then
        print_err "Failed to install JDK 17 via direct .deb extraction."
        exit 1
      fi
    else
      print_err "OpenJDK $jdk_version binary not found and it is not available via the direct-download fallback (only 17 is bundled). Fix apt or install it manually."
      exit 1
    fi
  fi
  if [ -x "$jdk_bin" ]; then
    print_info "JDK $jdk_version has been installed."
  else
    print_err "JDK $jdk_version could not be installed."
    exit 1
  fi
fi

jdk_dir="$SYSROOT/opt/openjdk"

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
echo ""
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"

if [ ! -d "$props_dir" ]; then
  mkdir -p "$props_dir"
fi

if [ ! -e "$props" ]; then
  printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
else
  if is_yes "$props file already exists. Would you like to overwrite it?"; then
    printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
  else
    print_err "Manually edit $SYSROOT/etc/ide-environment.properties file and set JAVA_HOME and ANDROID_SDK_ROOT."
  fi
fi

rm -vf "$downloaded_manifest"
print_success "Downloads completed. You are ready to go!"
