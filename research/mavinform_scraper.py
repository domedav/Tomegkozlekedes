#!/usr/bin/env python3
"""MÁV Mavinform Scraper - Egyszerűsített verzió"""

import re
import requests
import urllib3
from dataclasses import dataclass
from typing import List

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

BASE_URL = "https://www.mavcsoport.hu"


@dataclass
class MavinformItem:
    title: str
    link: str
    category: str       # "vonat", "busz", "helyi_busz", "ismeretlen"
    description: str = ""


def clean_html(text: str) -> str:
    text = re.sub(r'<[^>]+>', '', text)
    text = text.replace('&nbsp;', ' ').replace('&amp;', '&')
    text = text.replace('&ndash;', '–').replace('&gt;', '>').replace('&lt;', '<')
    text = re.sub(r'\s+', ' ', text)
    return text.strip()


def detect_category(block: str) -> str:
    lower = block.lower()
    if 'vonat_ikon' in lower:
        return "vonat"
    if 'volan-busz_ikon' in lower:
        return "busz"
    if 'helyi-busz_ikon' in lower:
        return "helyi_busz"
    return "ismeretlen"


def parse_list(html: str) -> List[MavinformItem]:
    items = []

    # Minden <div class="custom-news-item"> blokk
    blocks = re.split(r'<div class="custom-news-item">', html)

    for block in blocks[1:]:  # az első split nem item
        item = MavinformItem(title="", link="", category="ismeretlen")

        # Kategória
        item.category = detect_category(block)

        # Cím + Link: <h3 class="field-content"><a href="LINK">CIM</a></h3>
        title_match = re.search(
            r'<h3 class="field-content">\s*<a href="([^"]*)"[^>]*>(.*?)</a>',
            block, re.DOTALL
        )
        if title_match:
            item.link = title_match.group(1)
            if not item.link.startswith('http'):
                item.link = BASE_URL + item.link
            item.title = clean_html(title_match.group(2))

        if item.title:
            items.append(item)

    return items


def extract_divs_by_class(html: str, css_class: str) -> List[str]:
    """Kiegyensúlyozott <div class='...'> kinyerés (beágyazott div-ekkel is jó)"""
    out = []
    opens = [m for m in re.finditer(r'<div\b[^>]*class="%s"[^>]*>' % css_class, html)]
    tokens = [(m.start(), m.group(0)) for m in re.finditer(r'</?div\b[^>]*>', html)]
    for om in opens:
        depth = 0
        inner_start = -1
        for pos, tok in tokens:
            if pos < om.start():
                continue
            if tok.startswith('</'):
                depth -= 1
            else:
                if depth == 0 and pos == om.start():
                    inner_start = pos + len(tok)
                depth += 1
            if depth == 0 and inner_start >= 0:
                out.append(html[inner_start:pos])
                break
    return out


def join_blocks(inner_html: str) -> str:
    parts = []
    for m in re.finditer(r'<(p|li|h[1-6])\b[^>]*>(.*?)</\1>', inner_html, re.DOTALL | re.IGNORECASE):
        t = clean_html(m.group(2))
        if t:
            parts.append(t)
    if parts:
        return "\n\n".join(parts)
    return clean_html(inner_html)[:2000]


def fetch_detail(link: str) -> str:
    try:
        resp = requests.get(
            link,
            headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 13)'},
            timeout=30,
            verify=False
        )
        resp.encoding = 'utf-8'
        bodies = extract_divs_by_class(resp.text, "field-body")
        if not bodies:
            return ""
        return max((join_blocks(b) for b in bodies), key=len)
    except Exception as e:
        return f"[Hiba: {e}]"


def scrape(page: int = 0, with_description: bool = True) -> List[MavinformItem]:
    url = f"{BASE_URL}/mavinform" + (f"?page={page}" if page > 0 else "")

    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    resp = requests.get(
        url,
        headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 13)'},
        timeout=30,
        verify=False
    )
    resp.encoding = 'utf-8'

    items = parse_list(resp.text)

    if with_description:
        for item in items:
            item.description = fetch_detail(item.link)

    return items


if __name__ == "__main__":
    print("=== MÁV Mavinform Scraper ===\n")

    items = scrape(page=0, with_description=False)
    print(f"Talalt: {len(items)} item\n")

    for i, item in enumerate(items, 1):
        print(f"{i}. [{item.category.upper()}] {item.title}")
        print(f"   {item.link}")
        print()
