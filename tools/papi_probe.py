#!/usr/bin/env python3
"""PAPI konfig-felderítő: élő MÁV szervert kérdez, nem APK-t decompilál.

Működés:
  1. Betölti a repó gyökerében lévő papi_config.json-t (spoof + ismert path-ek).
  2. VersionInfo/GetVersionInfo-val feloldja az aktuális service-URL térképet
     (mentett -> pinnelt -> patch-szkennelt Management path-eken).
  3. A két kritikus service-t (userProfile, order) olcsó, auth-mentes
     hívással validálja (200 — akár businessError-rel — = él, 404 = halott);
     halott esetén patch-szkennel új path-et keres.
  4. Best-effort Play Store verzió-scrape (hu.mav.emmapp).
  5. Visszaírja a papi_config.json-t, ha változott.

Csak olvasó/validáló hívásokat végez, bejelentkezni nem próbál.
"""
import json
import re
import sys
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG_PATH = ROOT / "papi_config.json"
BASE = "https://mvapi.mav.hu/IN/PROD/"
# APKCombo: auth nélkül mutatja a legfrissebb MÁVPlusz verziót
# (a Play áruházhoz auth kell, ezért nem azt használjuk).
APKCOMBO_URL = "https://apkcombo.com/mavplusz/hu.mav.emmapp/"
SCAN_AHEAD = 12
TIMEOUT = 25

SERVICE_OPS = {
    # service-jsonmező (rövid név): (validáló op, body)
    "userProfile": ("Authentication/Login", {"username": "", "password": ""}),
    "order": ("PreviousPurchase/GetPreviousPurchases", {}),
}


def load_cfg() -> dict:
    return json.loads(CFG_PATH.read_text(encoding="utf-8"))


def headers(cfg: dict) -> dict:
    cv = cfg.get("clientVersion", "2.5.18-prod")
    return {
        "Content-Type": "application/json",
        "Accept": "text/plain",
        "language": "hu",
        "je-api-key": cfg.get("apiKey", ""),
        "je-partner-name": cfg.get("partnerName", "App2025"),
        "partner-session-name": str(uuid.uuid4()),
        "device-instance": str(uuid.uuid4()),
        "correlation-id": str(uuid.uuid4()),
        "Referer": "mavpluszmobile",
        "User-Agent": f"MAVApp/{cv} (hu.mav.emmapp; build: {cfg.get('build', '874')}; Android 14)",
    }


def post(path: str, body: dict, cfg: dict):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers=headers(cfg),
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception:
            return e.code, ""
    except Exception as e:
        return None, str(e)


def version_info(mgmt_path: str, cfg: dict):
    body = {
        "device": {"calendar": "gregorian", "kind": "android",
                   "localization": "hu", "regionFormat": "hu_HU",
                   "timeZone": "Europe/Budapest", "type": "android",
                   "version": "34"},
        "process": {"clientDate": "2026-01-01T00:00:00Z",
                    "clientId": f"Mav-Android-{cfg.get('clientVersion', '2.5.18-prod')}",
                    "clientInstanceID": str(uuid.uuid4()), "culture": "hu",
                    "httpUserAgent": headers(cfg)["User-Agent"],
                    "clientVersion": cfg.get("clientVersion", "2.5.18-prod")},
    }
    code, text = post(mgmt_path + "VersionInfo/GetVersionInfo", body, cfg)
    if code != 200:
        return None
    try:
        return json.loads(text)
    except Exception:
        return None


def mgmt_candidates(current: str):
    out = [current]
    m = re.match(r"^(Management/\d+_\d+_\d+)_(\d+)/$", current)
    if m:
        prefix, base = m.group(1), int(m.group(2))
        out += [f"{prefix}_{p}/" for p in range(base + 1, base + SCAN_AHEAD + 1)]
    return out


def svc_candidates(current: str):
    m = re.match(r"^([A-Za-z]+/\d+_\d+_\d+)_(\d+)/$", current)
    if not m:
        return [current]
    prefix, base = m.group(1), int(m.group(2))
    return [current] + [f"{prefix}_{p}/" for p in range(base + 1, base + SCAN_AHEAD + 1)]


def alive(path: str, op: str, body: dict, cfg: dict) -> bool:
    code, _ = post(path + op, body, cfg)
    # 200 (akár businessError-rel), 401/403/405 = az endpoint létezik.
    # 404 vagy hálózati hiba = halott.
    return code is not None and code != 404


def apkcombo_version() -> str | None:
    """Legfrissebb MÁVPlusz verzió APKCombo-ról (auth nem kell)."""
    try:
        req = urllib.request.Request(APKCOMBO_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            html = r.read().decode("utf-8", "replace")
        for pat in (r'"softwareVersion":\s*"([^"]+)"',
                    r"Download MÁVPlusz APK ([\w.\-]+)"):
            m = re.search(pat, html)
            if m:
                return m.group(1)
    except Exception as e:
        print(f"APKCombo scrape sikertelen (best-effort): {e}")
    return None


def main() -> int:
    cfg = load_cfg()
    orig_client = cfg.get("clientVersion")
    paths = dict(cfg.get("servicePaths", {}))
    mgmt = paths.get("management", "Management/4_13_0_11/")
    print(f"Management jelöltek {mgmt}-től...")

    papi_map = None
    force_upgrade = False
    for cand in mgmt_candidates(mgmt):
        root = version_info(cand, cfg)
        if root and isinstance(root.get("papiUrl"), dict):
            print(f"  ÉL: {cand}")
            papi_map = root["papiUrl"]
            force_upgrade = bool(root.get("forceUpgrade", False))
            if cand != mgmt:
                print(f"  Management verzióváltás: {mgmt} -> {cand}")
            mgmt = cand
            break
        print(f"  halott: {cand} ")

    if not papi_map:
        print("HIBA: egy Management path sem él, konfig változatlan.")
        return 1

    field_to_short = {
        "userProfileServiceUrl": "userProfile", "orderServiceUrl": "order",
        "managementServiceUrl": "management", "baseDataServiceUrl": "baseData",
        "logServiceUrl": "log", "offerServiceUrl": "offer",
        "refundServiceUrl": "refund", "schedulerServiceUrl": "scheduler",
    }
    for field, short in field_to_short.items():
        url = papi_map.get(field, "")
        m = re.search(r"/IN/PROD/(.+)", url)
        if m:
            p = m.group(1)
            if not p.endswith("/"):
                p += "/"
            paths[short] = p

    # Kritikus service-ek validálása + patch-szken halott esetén.
    for short, (op, body) in SERVICE_OPS.items():
        cur = paths.get(short, "")
        if alive(cur, op, body, cfg):
            print(f"  OK: {short} -> {cur}")
            continue
        print(f"  HALOTT: {short} -> {cur}, szken...")
        for cand in svc_candidates(cur)[1:]:
            if alive(cand, op, body, cfg):
                print(f"  ÚJ: {short} -> {cand}")
                paths[short] = cand
                break
        else:
            print(f"  HIBA: {short}-hez nincs élő path, régi marad.")

    pv = apkcombo_version()
    if pv:
        print(f"  APKCombo legfrissebb kliens: {pv} (konfigban: {cfg.get('clientVersion')})")
        if pv != cfg.get("clientVersion"):
            # A build-szám az APK-oldalról nem derül ki: verziót átvesszük,
            # build-et meghagyjuk + warning, hogy kézi ellenőrzés kellhet.
            print(f"::warning:: új MÁV kliensverzió: {pv} — clientVersion átírva, build kézi ellenőrzést kérhet")
            cfg["clientVersion"] = pv

    if force_upgrade:
        # A szerver szerint a spoofolt kliens elavult: kézi client-bump kell
        # (clientVersion/build a papi_config.json-ban + PapiConfig fallback).
        print("::warning:: forceUpgrade=true — a spoofolt kliensverzió elavult!")

    new_cfg = dict(cfg)
    new_cfg["servicePaths"] = paths
    changed = (new_cfg != load_cfg()) or (cfg.get("clientVersion") != orig_client)
    if changed:
        # A lecserélés előtt a régi (működő) konfigot .old-ba mentjük:
        # ha az újban valami rossz (pl. deprecated), egy mozdulattal
        # visszaállítható, amíg nincs kódjavítás.
        import shutil
        old_path = ROOT / "papi_config.json.old"
        shutil.copy(CFG_PATH, old_path)
        print(f"Régi konfig mentve: {old_path.name}")
        CFG_PATH.write_text(json.dumps(new_cfg, indent=2, ensure_ascii=False) + "\n",
                             encoding="utf-8")
        print("papi_config.json FRISSÍTVE.")
    else:
        print("papi_config.json változatlan.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
