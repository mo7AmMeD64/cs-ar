"""Collect every built .cs3 into repo/ and generate plugins.json + repo.json.

To add another plugin: create its folder (with build.gradle.kts), then add an
entry to PLUGINS below. To publish an update, bump that entry's "version".
"""
import json
import os
import shutil

REPO = "mo7AmMeD64/cs-cinemana"
BRANCH = "main"
RAW = f"https://raw.githubusercontent.com/{REPO}/{BRANCH}/repo"

PLUGINS = [
    {
        "internalName": "ShabakatyCinemana",
        "name": "Shabakaty Cinemana",
        "iconUrl": "https://cinemana.shabakaty.com/img/icone.png",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "CinemaBox",
        "name": "Cinema Box",
        "iconUrl": None,
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime", "Cartoon"],
        "language": "ar",
    },
    {
        "internalName": "MeowTv",
        "name": "MeowTV",
        "iconUrl": None,
        "version": 4,
        "tvTypes": ["Movie", "TvSeries", "Anime", "Cartoon"],
        "language": "en",
    },
    {
        "internalName": "Krmzy",
        "name": "قرمزي",
        "iconUrl": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRwTnAJOvyri3uHzHxjkEdlaBBKs8MAvIuJtFmCoo9u5qYiuFpHZjcl6tDi&s=10",
        "version": 1,
        "tvTypes": ["TvSeries", "Movie"],
        "language": "ar",
    },
    {
        "internalName": "Animewitcher",
        "name": "AnimeWitcher",
        "iconUrl": "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/anime_witcher_round_icon.png",
        "version": 3,
        "tvTypes": ["Anime"],
        "language": "ar",
    },
    {
        "internalName": "CinemaPlus",
        "name": "Cinema Plus",
        "iconUrl": "https://j.top4top.io/p_3472gwtpo1.jpg",
        "version": 1,
        "tvTypes": ["Movie"],
        "language": "ar",
    },
]

os.makedirs("repo", exist_ok=True)
entries = []
for p in PLUGINS:
    built = f"{p['internalName']}/build/{p['internalName']}.cs3"
    if not os.path.exists(built):
        raise SystemExit(f"missing build output: {built}")
    shutil.copy(built, "repo/")
    entries.append({
        "iconUrl": p["iconUrl"],
        "apiVersion": 1,
        "repositoryUrl": f"https://github.com/{REPO}",
        "fileSize": os.path.getsize(built),
        "status": 1,
        "language": p["language"],
        "authors": ["mo7AmMeD64"],
        "tvTypes": p["tvTypes"],
        "version": p["version"],
        "internalName": p["internalName"],
        "url": f"{RAW}/{p['internalName']}.cs3",
        "name": p["name"],
    })

with open("repo/plugins.json", "w", encoding="utf-8") as f:
    json.dump(entries, f, indent=2, ensure_ascii=False)

with open("repo/repo.json", "w", encoding="utf-8") as f:
    json.dump({
        "name": "mo7AmMeD64 Arabic Repo",
        "description": "Arabic CloudStream extensions by mo7AmMeD64",
        "manifestVersion": 1,
        "pluginLists": [f"{RAW}/plugins.json"],
    }, f, indent=2, ensure_ascii=False)

print(f"repo/ ready with {len(entries)} plugins")
