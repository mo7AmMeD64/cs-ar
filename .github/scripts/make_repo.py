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
        "iconUrl": "https://pbs.twimg.com/profile_images/1555882172178907136/jismNIAg_400x400.jpg",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "CinemaBox",
        "name": "Cinema Box",
        "iconUrl": "https://cinema.albox.co/_next/image?url=%2Ficons%2Fapple-touch-icon.png&w=96&q=75",
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime", "Cartoon"],
        "language": "ar",
    },
    {
        "internalName": "MeowTv",
        "name": "MeowTV",
        "iconUrl": "https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/icons/MeowTv.png",
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
        "iconUrl": "https://play-lh.googleusercontent.com/45Zr4KXlCZ6N3wAMKa8xPvZ0u-x27a78QRW4l7FRxaegfBQ6qAocdXU8l9QYb5EpM3vSUpDU3yHMQCNzpSXBWw",
        "version": 4,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AnimeMovie"],
        "language": "ar",
    },
    {
        "internalName": "CartoonDub",
        "name": "كرتون مدبلج",
        "iconUrl": "https://play-lh.googleusercontent.com/kuFOkLUbmgW0_XMmdMSFvHAvxpzOLbn2OrvYATSnCusFyNgQp76UAW5hyWfGA4Oqo2u8IjQujmNkbncMf0avNYw=w480-h960-rw",
        "version": 7,
        "tvTypes": ["TvSeries", "Movie", "Anime", "Cartoon"],
        "language": "ar",
    },
    {
        "internalName": "CimaCloud",
        "name": "Cima Cloud",
        "iconUrl": "https://cima-cloud.com/images/logo.png",
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AnimeMovie"],
        "language": "ar",
    },
    {
        "internalName": "MovieBoxProvider",
        "name": "MovieBox",
        "iconUrl": "https://img.utdstc.com/icon/dc7/59a/dc759aeb96e27c35acb29d74236106db8181ee6c6730c67002d710f1e54c2a1d:200",
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
        "internalName": "StarDima",
        "name": "ستارديما",
        "iconUrl": "https://stardima.app/logo.png",
        "version": 15,
        "tvTypes": ["Movie", "TvSeries", "Anime", "Cartoon", "AnimeMovie"],
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
        "version": 3,
        "tvTypes": ["TvSeries", "Anime", "Movie"],
        "language": "ar",
    },
    {
        "internalName": "ArabSeed",
        "name": "عرب سيد",
        "iconUrl": "https://imgs.search.brave.com/2yK7USwCO2MiQEiKCHFGOeQ4xzP3W1tf7-sgiiu-irc/rs:fit:32:32:1:0/g:ce/aHR0cDovL2Zhdmlj/b25zLnNlYXJjaC5i/cmF2ZS5jb20vaWNv/bnMvMDM4ZTE3ZDUw/YTQ3ZTkzNDBlZWQ5/YWU4MDU4OWY0YjM4/NDI1OTk1OGYwNDVk/YWZiNThhYjFiOGI0/MTlhY2UwYi9hcmFi/c2VlZC5jb2xsZWdl/Lw",
        "version": 3,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AsianDrama"],
        "language": "ar",
    },
    {
        "internalName": "Wecima",
        "name": "Wecima",
        "iconUrl": None,
        "version": 3,
        "tvTypes": ["Movie", "TvSeries", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "OscarTv",
        "name": "Oscar TV",
        "iconUrl": "https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/icons/OscarTv.png",
        "version": 3,
        "tvTypes": ["Movie", "TvSeries", "Anime", "TvChannel"],
        "language": "ar",
    },
    {
        "internalName": "FaselMeta",
        "name": "FaselHD Meta",
        "iconUrl": None,
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime"],
        "language": "ar",
    },
    {
        "internalName": "MyCimaProvider",
        "name": "MyCima",
        "iconUrl": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTxoF3lscri32oVf0iQlhbjFEoUn2xg3OmQIx4wYdDmqw&s",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AsianDrama"],
        "language": "ar",
    },
    {
        "internalName": "Aflaam",
        "name": "Aflaam",
        "iconUrl": "https://aflaam.com/style/assets/images/logo.png",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "3isk",
        "name": "قصة عشق",
        "iconUrl": "https://3esk.onl/wp-content/uploads/2021/01/3isk-logo.png",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "Akwam",
        "name": "Akwam",
        "iconUrl": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ2vuGLv4zY3XpoN2jza2unCQ95xFfypfe-h3k3xteMbGxl7taw-ziXyNg&s=10",
        "version": 3,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "Alooytv",
        "name": "AlooyTv",
        "iconUrl": "https://fitnur.com/uploads/avatars/c5ae8dadf88bcea9549657ec451a1563.jpg",
        "version": 1,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "VioLa",
        "name": "Vio-La",
        "iconUrl": "https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/icons/VioLa.png",
        "version": 2,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "Cee",
        "name": "Cee",
        "iconUrl": None,
        "version": 1,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "StreamPlay",
        "name": "StreamPlay",
        "iconUrl": None,
        "version": 687,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "en",
        "file": "StreamPlay/StreamPlay.cs3",
    },
    {
        "internalName": "AnimeDay",
        "name": "أنمي داي",
        "iconUrl": "https://www.anime-day.com/favicon.ico",
        "version": 23,
        "tvTypes": ["Anime", "TvSeries", "Movie", "Cartoon"],
        "language": "ar",
    },
    {
        "internalName": "AnimeRift",
        "name": "AnimeRift",
        "iconUrl": "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main/AnimeRift/icon.png",
        "version": 1,
        "tvTypes": ["TvSeries", "Anime", "Movie"],
        "language": "ar",
    },
    {
        "internalName": "DramaWorld",
        "name": "DramaWorld",
        "iconUrl": "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/DramaWorld.png",
        "version": 3,
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ar",
    },
    {
        "internalName": "Egywatch",
        "name": "إيجي واتش",
        "iconUrl": "https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/icons/Egywatch.png",
        "version": 2,
        "tvTypes": ["Movie", "TvSeries", "Anime", "AnimeMovie", "Live"],
        "language": "ar",
    },
    {
        "internalName": "Carateen",
        "name": "كراتين",
        "iconUrl": "https://carateen.tv/assets/img/favicons/apple-touch-icon.png",
        "version": 3,
        "tvTypes": ["Movie", "TvSeries", "Anime", "Cartoon", "Live"],
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
        "name": "CS-AR",
        "description": "اضافات عربية لكلاود ستريم",
        "iconUrl": f"{RAW}/icon.png",
        "manifestVersion": 1,
        "pluginLists": [f"{RAW}/plugins.json"],
    }, f, indent=2, ensure_ascii=False)

print(f"repo/ ready with {len(entries)} plugins")
