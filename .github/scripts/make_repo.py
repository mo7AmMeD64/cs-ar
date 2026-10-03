"""Collect every built .cs3 into repo/ and generate plugins.json + repo.json.

To add another plugin: create its folder (with build.gradle.kts), then add an
entry to PLUGINS below. To publish an update, bump that entry's "version".
A plugin with a "file" key is shipped PREBUILT: the .cs3 is committed as-is
(no build.gradle.kts in its folder, so gradle never builds it).
"""
import json
import os
import shutil

REPO = "mo7AmMeD64/cs-ar"
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
        "version": 4,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AnimeMovie"],
        "language": "ar",
    },
    {
        "internalName": "CartoonDub",
        "name": "كرتون مدبلج",
        "iconUrl": "https://imgs1.e-droid.net/srv/imgs/cards/c4046384_8106043_main.png?v=1",
        "version": 7,
        "tvTypes": ["TvSeries", "Movie", "Anime", "Cartoon"],
        "language": "ar",
    },
    {
        "internalName": "CimaCloud",
        "name": "Cima Cloud",
        "iconUrl": None,
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AnimeMovie"],
        "language": "ar",
    },
    {
        "internalName": "MovieBoxProvider",
        "name": "MovieBox",
        "iconUrl": None,
        "version": 52,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "hi",
        "file": "MovieBox/MovieBoxProvider.cs3",
    },
    {
        "internalName": "Egydead",
        "name": "EgyDead",
        "iconUrl": "https://yt3.googleusercontent.com/ytc/AIdro_kgVTM6DJtx3tcS4gkPOOPnwFXKNhsrFyMRigWOlWomuQ=s900-c-k-c0x00ffffff-no-rj",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AsianDrama"],
        "language": "ar",
    },
    {
        "internalName": "Anim3rb",
        "name": "Anime3rb",
        "iconUrl": "https://images.anime3rb.com/favicon/apple-touch-icon.png",
        "version": 1,
        "tvTypes": ["TvSeries", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "Anime-Phoenix",
        "name": "Anime Phoenix",
        "iconUrl": "https://yt3.googleusercontent.com/mBIIR5cqQC4Y-o7HaxbkJfs305X6tbHLSNPk6MRMCDJsH8xP0SfGhK-CBDpSmH95wSff9z99sg=s900-c-k-c0x00ffffff-no-rj",
        "version": 1,
        "tvTypes": ["TvSeries", "Anime", "Movie"],
        "language": "ar",
    },
    {
        "internalName": "Topcinema",
        "name": "Top Cinema",
        "iconUrl": "https://web8.topcinema.cam/wp-content/uploads/2023/05/cropped-icon-32x32.png",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "Stardima",
        "name": "ستارديما",
        "iconUrl": "https://web8.topcinema.cam/wp-content/uploads/2023/05/cropped-icon-32x32.png",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries", "Cartoon", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "Yacintv",
        "name": "Yacine TV",
        "iconUrl": "https://yt3.googleusercontent.com/ulm35tweg3do5istps0TgCjMmJSVczGUL2NIrXMwI1DDRi5ty29BIzQSUHVgqZN5CSo1PHhiA6M=s900-c-k-c0x00ffffff-no-rj",
        "version": 1,
        "tvTypes": ["TvSeries", "Live", "Movie"],
        "language": "ar",
    },
    {
        "internalName": "AnimeSlayer",
        "name": "أنمي سلاير",
        "iconUrl": "https://imgs.search.brave.com/HEQ0yh4UMc8dkTZOs7RRAz7s0pOVz2QlPCK5IZPWQvI/rs:fit:32:32:1:0/g:ce/aHR0cDovL2Zhdmlj/b25zLnNlYXJjaC5i/cmF2ZS5jb20vaWNv/bnMvMGRiZTMwOWQw/YmFkMjg2NGM0YmE0/MWNiMzYzMzhjZmVk/Nzc4MTNkZTRkYjg3/ODU1MTM5NDM3Yjlh/NmMzZDNjMS9hbmlt/ZS1zbGF5ZXIubmV0/Lw",
        "version": 2,
        "tvTypes": ["TvSeries", "Anime", "Movie"],
        "language": "ar",
    },
]

os.makedirs("repo", exist_ok=True)
entries = []
for p in PLUGINS:
    built = p.get("file") or f"{p['internalName']}/build/{p['internalName']}.cs3"
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
