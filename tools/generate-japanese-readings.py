# -*- coding: utf-8 -*-
"""Regenerates the Japanese reading tables in

    core/base/src/main/resources/de/mm20/launcher2/search/japanese

from the upstream dictionaries. Run it when the dictionaries are updated:

    python tools/generate-japanese-readings.py

Both sources are published by the Electronic Dictionary Research and Development
Group under the Creative Commons Attribution-ShareAlike 4.0 license, which the
generated files have to keep attributing.
"""
import gzip, io, os, sys, urllib.request
import xml.etree.ElementTree as ET

SOURCES = {
    'kanjidic2.xml.gz': 'https://www.edrdg.org/kanjidic/kanjidic2.xml.gz',
    'JMdict_e.gz': 'https://www.edrdg.org/pub/Nihongo/JMdict_e.gz',
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'core', 'base', 'src', 'main', 'resources', 'de', 'mm20',
                   'launcher2', 'search', 'japanese')
D = os.path.join(ROOT, 'build', 'japanese-readings')


def download():
    os.makedirs(D, exist_ok=True)
    for name, url in SOURCES.items():
        path = os.path.join(D, name)
        if os.path.exists(path):
            print('have', name)
            continue
        print('downloading', url)
        urllib.request.urlretrieve(url, path)


HEADER = """\
# Japanese readings, used by JapaneseSearchKeys to match a kanji label against a
# query typed in kana or in latin script. Generated, do not edit by hand; see
# tools/generate-japanese-readings.py.
#
# Derived from {source} by the Electronic Dictionary Research and Development
# Group, used under the Creative Commons Attribution-ShareAlike 4.0 license.
# https://www.edrdg.org/edrdg/licence.html
#
# One entry per line, tab separated: the written form followed by its readings.
# Sorted by the written form so that the file can be binary searched in place.
"""

def is_kanji(c):
    o = ord(c)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or o == 0x3005

def is_kana(c):
    o = ord(c)
    return 0x3041 <= o <= 0x309F or 0x30A1 <= o <= 0x30FC

def is_bmp(s):
    return all(ord(c) <= 0xFFFF for c in s)

def to_katakana(s):
    return ''.join(chr(ord(c) + 0x60) if 0x3041 <= ord(c) <= 0x3096 else c for c in s)


def kanji_table():
    """literal -> up to four readings, ordered on, on, kun, kun."""
    entries = []
    with gzip.open(os.path.join(D, 'kanjidic2.xml.gz'), 'rb') as f:
        for _, elem in ET.iterparse(f, events=('end',)):
            if elem.tag != 'character':
                continue
            lit = elem.findtext('literal')
            misc = elem.find('misc')
            grade = misc.findtext('grade') if misc is not None else None
            freq = misc.findtext('freq') if misc is not None else None
            jlpt = misc.findtext('jlpt') if misc is not None else None
            on, kun = [], []
            for r in elem.iter('reading'):
                t, v = r.get('r_type'), (r.text or '').strip()
                if t == 'ja_on':
                    v = v.replace('-', '').replace('.', '')
                    if v and v not in on:
                        on.append(v)
                elif t == 'ja_kun':
                    v = to_katakana(v.lstrip('-').split('.')[0].replace('-', ''))
                    if v and v not in kun:
                        kun.append(v)
            elem.clear()
            if not (grade or freq or jlpt) or not is_bmp(lit):
                continue
            # Interleave so that truncating the list keeps the most likely readings.
            ordered = []
            for r in (on[:1] + on[1:2] + kun[:1] + kun[1:2] + on[2:3] + kun[2:3]):
                if r and r not in ordered:
                    ordered.append(r)
            if ordered:
                entries.append((lit, ordered[:4]))
    entries.sort()
    return entries


def word_table():
    """written form -> up to two readings, for entries JMdict marks as common."""
    PRI = {'news1', 'news2', 'ichi1', 'ichi2', 'spec1', 'spec2', 'gai1', 'gai2'}
    SKIP_K = {'&iK;', '&oK;', '&rK;', '&sK;'}
    SKIP_R = {'&ok;', '&sk;', '&ik;'}
    words = {}
    with gzip.open(os.path.join(D, 'JMdict_e.gz'), 'rb') as f:
        for _, elem in ET.iterparse(f, events=('end',)):
            if elem.tag != 'entry':
                continue
            kebs = []
            for k in elem.findall('k_ele'):
                keb = k.findtext('keb')
                pri = {p.text for p in k.findall('ke_pri')}
                inf = {p.text for p in k.findall('ke_inf')}
                if keb and pri & PRI and not (inf & SKIP_K):
                    kebs.append(keb)
            rebs = []
            for r in elem.findall('r_ele'):
                reb = r.findtext('reb')
                inf = {p.text for p in r.findall('re_inf')}
                if reb and not (inf & SKIP_R):
                    rebs.append(to_katakana(reb))
            elem.clear()
            for keb in kebs:
                # Single kanji are covered by the kanji table, and anything longer than an
                # app name is never going to be looked up.
                if not (2 <= len(keb) <= 8) or not is_bmp(keb):
                    continue
                if not any(is_kanji(c) for c in keb):
                    continue
                if not all(is_kanji(c) or is_kana(c) for c in keb):
                    continue
                readings = words.setdefault(to_katakana(keb), [])
                for reb in rebs:
                    if reb not in readings and len(readings) < 2:
                        readings.append(reb)
    return sorted((k, v) for k, v in words.items() if v)


def write(path, header_source, entries):
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(HEADER.format(source=header_source))
        for k, v in entries:
            f.write(k + '\t' + '\t'.join(v) + '\n')
    size = os.path.getsize(path)
    print('%s: %d entries, %d bytes' % (os.path.basename(path), len(entries), size))

download()
write(os.path.join(OUT, 'kanji-readings.txt'), 'KANJIDIC2', kanji_table())
write(os.path.join(OUT, 'word-readings.txt'), 'JMdict', word_table())
