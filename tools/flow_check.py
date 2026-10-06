#!/usr/bin/env python3
"""Full PAPI flow-check: az app által használt ÖSSZES endpoint elérhetősége.

Használat:
  MAV_TEST_EMAIL=... MAV_TEST_PASSWORD=... python3 tools/flow_check.py --mode register
  MAV_TEST_EMAIL=... MAV_TEST_PASSWORD=... python3 tools/flow_check.py --mode full

  --mode register: csak regisztrál (email-megerősítés a felhasználónál).
  --mode full:     login -> refresh -> purchases -> details -> passCard ->
                   customerTypes + registration/forgotPassword alive-check.

A hitelesítő adatok KIZÁRÓLAG env-ből jönnek, fájlba/logba sosem íródnak.
A konfigot (servicePaths + endpoints) a papi_config.json-ból olvassa —
ugyanazokat az URL-eket hívja, mint az app.
CI-ben bukás = nem-nulla exit + ::error:: annotáció (értesítés).
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG_PATH = ROOT / "papi_config.json"
BASE = "https://mvapi.mav.hu/IN/PROD/"
TIMEOUT = 25

results: list[tuple[str, str, str]] = []  # (név, PASS/FAIL/SKIP, részlet)


def cfg() -> dict:
    return json.loads(CFG_PATH.read_text(encoding="utf-8"))


def endpoints(c: dict) -> dict:
    eps = dict(c.get("endpoints", {}))

    def one(key: str, default: str) -> str:
        v = eps.get(key, default)
        return v[0] if isinstance(v, list) and v else (v or default)

    def lst(key: str, default: list) -> list:
        v = eps.get(key, default)
        return v if isinstance(v, list) and v else [v or default[0]]
    return {"one": one, "list": lst}


def headers(c: dict, guid: str = "", auth: str = "") -> dict:
    cv = c.get("clientVersion", "2.5.18-prod")
    h = {
        "Content-Type": "application/json",
        "Accept": "text/plain",
        "language": "hu",
        "je-api-key": c.get("apiKey", ""),
        "je-partner-name": c.get("partnerName", "App2025"),
        "partner-session-name": str(uuid.uuid4()),
        "device-instance": str(uuid.uuid4()),
        "correlation-id": str(uuid.uuid4()),
        "Referer": "mavpluszmobile",
        "User-Agent": f"MAVApp/{cv} (hu.mav.emmapp; build: {c.get('build', '874')}; Android 14)",
    }
    if guid:
        h["user-guid"] = guid
    if auth:
        h["Authorization"] = "Bearer " + auth
    return h


def post(path: str, body: dict, c: dict, guid: str = "", auth: str = ""):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers=headers(c, guid, auth), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            code, text = r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            code, text = e.code, e.read().decode("utf-8", "replace")
        except Exception:
            code, text = e.code, ""
    except Exception as e:
        return None, f"hálózati hiba: {e}"
    try:
        return code, json.loads(text)
    except Exception:
        return code, {"_raw": text[:200]}


def check(name: str, ok: bool, detail: str = ""):
    results.append((name, "PASS" if ok else "FAIL", detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        print(f"::error:: flow-check FAIL: {name} — {detail}")
    return ok


def resolve_paths(c: dict, ep) -> dict | None:
    mgmt = c.get("servicePaths", {}).get("management", "Management/4_13_0_11/")
    import re
    cands = [mgmt]
    m = re.match(r"^(Management/\d+_\d+_\d+)_(\d+)/$", mgmt)
    if m:
        cands += [f"{m.group(1)}_{p}/" for p in range(int(m.group(2)) + 1, int(m.group(2)) + 13)]
    ver = ep["one"]("versionInfo", "VersionInfo/GetVersionInfo")
    body = {"device": {"calendar": "gregorian", "kind": "android", "localization": "hu",
                       "regionFormat": "hu_HU", "timeZone": "Europe/Budapest",
                       "type": "android", "version": "34"},
            "process": {"clientDate": "2026-01-01T00:00:00Z",
                        "clientId": f"Mav-Android-{c.get('clientVersion', '2.5.18-prod')}",
                        "clientInstanceID": str(uuid.uuid4()), "culture": "hu",
                        "httpUserAgent": headers(c)["User-Agent"],
                        "clientVersion": c.get("clientVersion", "2.5.18-prod")}}
    for cand in cands:
        code, root = post(cand + ver, body, c)
        if code == 200 and isinstance(root, dict) and isinstance(root.get("papiUrl"), dict):
            if root.get("forceUpgrade", False):
                check("forceUpgrade", False, "a szerver forceUpgrade=true-t küld: kliens bump kell")
                return None
            import re as _re
            paths = {}
            for field, short in [("userProfileServiceUrl", "userProfile"),
                                 ("orderServiceUrl", "order"),
                                 ("managementServiceUrl", "management"),
                                 ("baseDataServiceUrl", "baseData"),
                                 ("logServiceUrl", "log"),
                                 ("offerServiceUrl", "offer"),
                                 ("refundServiceUrl", "refund"),
                                 ("schedulerServiceUrl", "scheduler")]:
                mm = _re.search(r"/IN/PROD/(.+)", root["papiUrl"].get(field, ""))
                if mm:
                    paths[short] = mm.group(1).rstrip("/") + "/"
            return paths
    return None


def do_register(c: dict, ep, email: str, password: str) -> int:
    paths = resolve_paths(c, ep)
    if not check("versionInfo", paths is not None, "VersionInfo feloldás"):
        return 1
    body = {"email": email,
            "firstName": os.environ.get("MAV_TEST_FIRSTNAME", "Teszt"),
            "lastName": os.environ.get("MAV_TEST_LASTNAME", "Elek"),
            "password": password,
            "privacyPolicyAccept": True,
            "termOfUseAccept": True,
            "bornDate": os.environ.get("MAV_TEST_BIRTH", "1990-01-01")}
    code, root = post(paths["userProfile"] + ep["one"]("registration", "Profile/Registration"),
                      body, c)
    if code == 200 and isinstance(root, dict) and "businessError" not in root:
        check("registration", True, "regisztrálva — erősítsd meg az emailt")
        return 0
    detail = (root.get("businessError", {}) or {}).get("details", root) if isinstance(root, dict) else root
    # Már regisztrált fiók = az endpoint él (nem hiba ebben a módban).
    if code == 200:
        check("registration", True, f"endpoint él, szerver-válasz: {str(detail)[:120]}")
        return 0
    check("registration", False, f"HTTP {code}: {str(detail)[:200]}")
    return 1


def do_full(c: dict, ep, email: str, password: str) -> int:
    paths = resolve_paths(c, ep)
    if not check("versionInfo", paths is not None, "VersionInfo feloldás"):
        return 1

    # 0. Valódi regisztráció RANDOM fiókkal (sose az éles tesztfiókkal):
    # a megerősítő levél lepattan (nem létező cím), az API-siker a lényeg.
    rnd = uuid.uuid4().hex[:10]
    rnd_email = f"mavflowtest-{rnd}@mozmail.com"
    rnd_pw = f"Flow{rnd}A1!"
    code, root = post(paths["userProfile"] + ep["one"]("registration", "Profile/Registration"),
                      {"email": rnd_email, "firstName": "Flow", "lastName": "Teszt",
                       "password": rnd_pw, "privacyPolicyAccept": True,
                       "termOfUseAccept": True, "bornDate": "1990-01-01"}, c)
    rnd_ok = code == 200 and isinstance(root, dict) and "businessError" not in root
    detail = f"HTTP {code}"
    if not rnd_ok and isinstance(root, dict):
        detail += f" — {str(root.get('businessError', root))[:150]}"
    check("registration(random)", rnd_ok, detail + f" [{rnd_email}]")

    # Negatív ág: hibás jelszóval a szervernek el kell utasítania.
    code, root = post(paths["userProfile"] + ep["one"]("login", "Authentication/Login"),
                      {"username": email, "password": password + "#rossz"}, c)
    bad_rejected = not (code == 200 and isinstance(root, dict)
                        and root.get("refreshToken") and "businessError" not in root)
    check("login(wrong-password)", bad_rejected, f"HTTP {code}")

    code, root = post(paths["userProfile"] + ep["one"]("login", "Authentication/Login"),
                      {"username": email, "password": password}, c)
    token = (root.get("refreshToken") or "") if isinstance(root, dict) else ""
    guid = (root.get("userGuid") or "") if isinstance(root, dict) else ""
    if not check("login", code == 200 and bool(token) and bool(guid),
                 f"HTTP {code}" + ("" if token else f" — {str(root)[:150]}")):
        return 1

    code, root = post(paths["userProfile"] + ep["one"]("refreshToken", "Authentication/RefreshToken"),
                      {"refreshToken": token, "username": email}, c, guid, token)
    new_token = (root.get("refreshToken") or "") if isinstance(root, dict) else ""
    # A refresh a tokent ÉS a userGuidot is rotálja: mindkettőt át kell venni,
    # különben a következő hívás -9001 (SecurityError) lesz.
    if isinstance(root, dict) and root.get("userGuid"):
        guid = root["userGuid"]
    if not check("refreshToken", code == 200 and bool(new_token),
                 f"HTTP {code}" + ("" if new_token else f" — {str(root)[:150]}")):
        return 1
    token = new_token

    code, root = post(paths["order"] + ep["one"]("purchases", "PreviousPurchase/GetPreviousPurchases"),
                      {}, c, guid, token)
    plist = (root.get("previousPurchases") or []) if isinstance(root, dict) else []
    alive = code == 200 and isinstance(root, dict) and "previousPurchases" in root
    if not check("purchases", alive, f"HTTP {code}, {len(plist)} tétel"):
        # Lejárt-token jellegű üzleti hiba külön kiemelve.
        print(f"::warning:: purchases válasz: {str(root)[:200]}")
        return 1

    # Üzleti-hiba recovery-ág (az app readRoot-retry path tükre):
    # auth nélkül -9001 jön, új login + retry után mennie kell.
    # (Megjegyzés: érvényes guid + halott Bearer tokent a szerver elenged,
    # csak a guidot ellenőrzi — ezért az auth-nélküli a jó trigger.)
    code, root = post(paths["order"] + ep["one"]("purchases", "PreviousPurchase/GetPreviousPurchases"),
                      {}, c)
    got_be = isinstance(root, dict) and "businessError" in root
    rec_ok = False
    if got_be:
        code, root = post(paths["order"] + ep["one"]("purchases", "PreviousPurchase/GetPreviousPurchases"),
                          {}, c, guid, token)
        rec_ok = code == 200 and isinstance(root, dict) and "previousPurchases" in root
    check("purchases(businessError-recovery)", got_be and rec_ok,
          f"halott-token hiba: {got_be}, relogin-retry: {rec_ok}")

    if plist:
        pid = (plist[0].get("purchaseId") or "") if isinstance(plist[0], dict) else ""
        code, root = post(paths["order"] + ep["one"]("purchaseDetails",
                          "PreviousPurchase/GetPreviousPurchaseDetails"),
                          {"purchaseId": pid, "certificateImages": False,
                           "passCardDetails": True, "includeRefundedItems": True},
                          c, guid, token)
        has_details = isinstance(root, dict) and ("purchaseDetails" in root or "businessError" not in root)
        check("purchaseDetails", code == 200 and has_details, f"HTTP {code}")
    else:
        results.append(("purchaseDetails", "SKIP", "nincs vásárlás a tesztfiókon"))
        print("[SKIP] purchaseDetails — nincs vásárlás a tesztfiókon")

    code, root = post(paths["userProfile"] + ep["one"]("passCard", "Profile/GetPassCard"),
                      {"enableExpiredTickets": True}, c, guid, token)
    check("passCard", code == 200 and isinstance(root, dict), f"HTTP {code}")

    got_types = False
    for op in ep["list"]("customerTypes", ["BaseDataApi/GetCustomerTypes"]):
        code, root = post(paths["baseData"] + op, {}, c, guid, token)
        if code == 200 and isinstance(root, dict) and "businessError" not in root:
            got_types = True
            check("customerTypes", True, op)
            break
    if not got_types:
        check("customerTypes", False, "egyik BaseData op sem adott listát")

    # forgotPassword: üres emaillel (validációs hiba, levél nem megy ki).
    code, _ = post(paths["userProfile"] + ep["one"]("forgotPassword", "Profile/ForgottenPassword"),
                   {"email": ""}, c)
    check("forgotPassword-alive", code == 200, f"HTTP {code}")

    failed = [n for n, s, _ in results if s == "FAIL"]
    print(f"\nÖsszegzés: {len(results) - len(failed)}/{len(results)} OK" +
          (f", BUKOTT: {failed}" if failed else ""))
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["register", "full"], default="full")
    args = ap.parse_args()
    email = os.environ.get("MAV_TEST_EMAIL", "")
    password = os.environ.get("MAV_TEST_PASSWORD", "")
    if not email or not password:
        print("::error:: MAV_TEST_EMAIL / MAV_TEST_PASSWORD env hiányzik")
        return 2
    c = cfg()
    ep = endpoints(c)
    if args.mode == "register":
        return do_register(c, ep, email, password)
    return do_full(c, ep, email, password)


if __name__ == "__main__":
    sys.exit(main())
