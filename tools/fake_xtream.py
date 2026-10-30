#!/usr/bin/env python3
"""
Faux serveur Xtream Codes pour les captures d'écran automatiques (CI uniquement).
Catalogue réaliste (chaînes, films, séries, guide TV), affiches depuis picsum.photos,
logos de chaînes générés, et une vidéo de test pour le lecteur.

    python3 tools/fake_xtream.py 8080 /chemin/vers/test.ts
"""
import base64
import hashlib
import json
import os
import random
import sys
import time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
VIDEO = sys.argv[2] if len(sys.argv) > 2 else ""
HOST = "http://10.0.2.2:%d" % PORT
NOW = int(time.time())
rnd = random.Random(42)

LIVE_CATS = [
    ("1", "FR | GÉNÉRALISTES"), ("2", "FR | SPORT"), ("3", "FR | CINÉMA"), ("4", "FR | INFO"),
    ("5", "FR | JEUNESSE"), ("6", "FR | DOCUMENTAIRES"), ("7", "FR | MUSIQUE"), ("8", "UK | ENTERTAINMENT"),
]
CHANNELS = [
    ("1", "TF1", "TF1.fr", "#1D4ED8"), ("1", "France 2", "France2.fr", "#E11D48"), ("1", "France 3", "France3.fr", "#2563EB"),
    ("1", "Canal+", "CanalPlus.fr", "#111111"), ("1", "France 5", "France5.fr", "#16A34A"), ("1", "M6", "M6.fr", "#7C3AED"),
    ("1", "Arte", "Arte.fr", "#EA580C"), ("1", "C8", "C8.fr", "#0EA5E9"), ("1", "W9", "W9.fr", "#DB2777"),
    ("1", "TMC", "TMC.fr", "#0F766E"), ("1", "TFX", "TFX.fr", "#9333EA"), ("1", "NRJ 12", "NRJ12.fr", "#DC2626"),
    ("2", "beIN Sports 1", "BeINSports1.fr", "#5B21B6"), ("2", "beIN Sports 2", "BeINSports2.fr", "#6D28D9"),
    ("2", "beIN Sports 3", "BeINSports3.fr", "#7C3AED"), ("2", "Canal+ Sport", "CanalPlusSport.fr", "#111111"),
    ("2", "Canal+ Foot", "CanalPlusFoot.fr", "#111111"), ("2", "RMC Sport 1", "RMCSport1.fr", "#B91C1C"),
    ("2", "Eurosport 1", "Eurosport1.fr", "#1E3A8A"), ("2", "L'Équipe", "LEquipe.fr", "#DC2626"),
    ("2", "Ligue 1+", "Ligue1Plus.fr", "#0EA5E9"),
    ("3", "Ciné+ Premier", "CinePlusPremier.fr", "#B45309"), ("3", "Ciné+ Frisson", "CinePlusFrisson.fr", "#7F1D1D"),
    ("3", "OCS Max", "OCSMax.fr", "#EA580C"), ("3", "TCM Cinéma", "TCM.fr", "#374151"), ("3", "Paramount Channel", "Paramount.fr", "#1D4ED8"),
    ("4", "BFMTV", "BFMTV.fr", "#1E40AF"), ("4", "CNews", "CNews.fr", "#0F172A"), ("4", "franceinfo", "Franceinfo.fr", "#DC2626"),
    ("4", "LCI", "LCI.fr", "#1D4ED8"), ("4", "France 24", "France24.fr", "#0369A1"), ("4", "Euronews", "Euronews.fr", "#0EA5E9"),
    ("5", "Gulli", "Gulli.fr", "#F59E0B"), ("5", "Disney Channel", "DisneyChannel.fr", "#2563EB"), ("5", "Cartoon Network", "CartoonNetwork.fr", "#111827"),
    ("5", "Boomerang", "Boomerang.fr", "#0EA5E9"), ("5", "Nickelodeon", "Nickelodeon.fr", "#F97316"),
    ("6", "National Geographic", "NatGeo.fr", "#EAB308"), ("6", "Discovery", "Discovery.fr", "#1D4ED8"),
    ("6", "Planète+", "PlanetePlus.fr", "#16A34A"), ("6", "Histoire TV", "HistoireTV.fr", "#92400E"),
    ("7", "MTV", "MTV.fr", "#111111"), ("7", "M6 Music", "M6Music.fr", "#7C3AED"), ("7", "Trace Urban", "TraceUrban.fr", "#111111"),
    ("8", "BBC One", "BBCOne.uk", "#111111"), ("8", "ITV 1", "ITV1.uk", "#16A34A"),
]

VOD_CATS = [
    ("10", "FR | NOUVEAUTÉS"), ("11", "FR | ACTION"), ("12", "FR | COMÉDIE"), ("13", "FR | MANGA"),
    ("14", "FR | ANIMATION"), ("15", "FR | SCIENCE-FICTION"), ("16", "FR | DRAME"), ("17", "FR | THRILLER"), ("18", "FR | 4K UHD"),
]
MOVIES = {
    "10": ["Dune : Deuxième partie", "Oppenheimer", "Le Comte de Monte-Cristo", "Gladiator II", "Wicked", "Vaiana 2",
           "Un p'tit truc en plus", "L'Amour ouf", "Joker : Folie à deux", "Deadpool & Wolverine"],
    "11": ["John Wick 4", "Mission : Impossible – Dead Reckoning", "Top Gun : Maverick", "The Batman", "Extraction 2",
           "Furiosa", "Tyler Rake", "Fast X", "The Equalizer 3", "Bad Boys : Ride or Die", "Balle perdue", "Athena"],
    "12": ["Intouchables", "Le Dîner de cons", "Les Trois Frères", "Bienvenue chez les Ch'tis", "OSS 117 : Le Caire, nid d'espions",
           "La Cité de la peur", "Astérix : Mission Cléopâtre", "Qu'est-ce qu'on a fait au Bon Dieu ?", "Le Sens de la fête", "Barbie"],
    "13": ["Your Name", "Le Voyage de Chihiro", "Suzume", "Demon Slayer : Le Train de l'infini", "Jujutsu Kaisen 0",
           "One Piece Film : Red", "Akira", "Le Garçon et le Héron", "Princesse Mononoké", "Mon voisin Totoro", "Ghost in the Shell", "Perfect Blue"],
    "14": ["Vice-versa 2", "Spider-Man : Across the Spider-Verse", "Le Roi lion", "Coco", "Soul", "Ratatouille", "Là-haut",
           "Le Robot sauvage", "Moi, moche et méchant 4", "Kung Fu Panda 4"],
    "15": ["Interstellar", "Blade Runner 2049", "Inception", "Premier Contact", "The Creator", "Seul sur Mars", "Matrix",
           "Avatar : La Voie de l'eau", "Gravity", "Tenet"],
    "16": ["Anatomie d'une chute", "Les Misérables", "La Vie d'Adèle", "Parasite", "Whiplash", "The Whale", "Past Lives",
           "Le Bureau des légendes", "Petite Maman", "Aftersun"],
    "17": ["Gone Girl", "Prisoners", "Seven", "Shutter Island", "Le Silence des agneaux", "Zodiac", "Nightcrawler", "Old Boy",
           "Memories of Murder", "Sicario"],
    "18": ["Dune 4K", "Oppenheimer 4K", "Interstellar 4K", "Avatar 4K", "Top Gun : Maverick 4K", "Blade Runner 2049 4K"],
}
SERIES_CATS = [("20", "FR | SÉRIES NOUVEAUTÉS"), ("21", "FR | SÉRIES DRAME"), ("22", "FR | SÉRIES COMÉDIE"), ("23", "FR | MANGA SÉRIES"), ("24", "FR | DOCUSÉRIES")]
SERIES = {
    "20": ["The Last of Us", "House of the Dragon", "Shōgun", "Fallout", "The Bear", "Severance", "Arcane", "Lupin"],
    "21": ["Breaking Bad", "Better Call Saul", "Succession", "The Crown", "Mad Men", "Le Bureau des légendes", "Peaky Blinders", "Chernobyl"],
    "22": ["The Office", "Kaamelott", "Friends", "Parks and Recreation", "Ted Lasso", "Brooklyn Nine-Nine", "Platane", "Fleabag"],
    "23": ["One Piece", "L'Attaque des Titans", "Demon Slayer", "Jujutsu Kaisen", "Naruto Shippuden", "Death Note", "Frieren", "Solo Leveling"],
    "24": ["Planète Terre III", "Formula 1 : Pilotes de leur destin", "Tour de France : Au cœur du peloton", "Notre planète"],
}

PLOTS = [
    "Une course contre la montre où chaque choix a un prix, portée par une mise en scène spectaculaire et une musique inoubliable.",
    "Entre loyauté et ambition, un héros ordinaire se retrouve au cœur d'un affrontement qui le dépasse.",
    "Un récit intime et lumineux sur la famille, le temps qui passe et les secrets que l'on garde.",
    "Quand le passé refait surface, une enquête haletante fait vaciller toutes les certitudes.",
    "Une aventure épique, drôle et émouvante, à voir et revoir en famille.",
]


def slug(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()[:10]


def poster(name):
    return "https://picsum.photos/seed/%s/400/600" % slug(name)


def backdrop(name):
    return "https://picsum.photos/seed/%s-bd/1280/720" % slug(name)


def logo_url(i):
    return "%s/logo/%d.png" % (HOST, i)


_logo_cache = {}


def logo_png(i):
    if i in _logo_cache:
        return _logo_cache[i]
    data = b""
    try:
        from PIL import Image, ImageDraw, ImageFont
        cat, name, _, color = CHANNELS[i]
        img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((8, 8, 248, 248), radius=56, fill=color)
        txt = name.replace("Sports", "").replace("Sport", "").strip()
        words = txt.split()
        label = (words[0][:6] if len(words[0]) > 2 else txt[:6]).upper()
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 64 if len(label) <= 4 else 46)
        except Exception:
            font = ImageFont.load_default()
        w = d.textlength(label, font=font)
        d.text(((256 - w) / 2, 92), label, fill="white", font=font)
        import io
        buf = io.BytesIO()
        img.save(buf, "PNG")
        data = buf.getvalue()
    except Exception as e:  # Pillow absent : petit PNG uni
        print("logo", e)
    _logo_cache[i] = data
    return data


def live_streams():
    out = []
    for i, (cat, name, epg, _) in enumerate(CHANNELS):
        quality = " FHD" if i % 3 == 0 else (" HD" if i % 3 == 1 else "")
        out.append({
            "num": i + 1, "name": "FR| " + name + quality if not epg.endswith(".uk") else "UK| " + name,
            "stream_type": "live", "stream_id": 1000 + i, "stream_icon": logo_url(i),
            "epg_channel_id": epg, "added": str(NOW - 86400 * 30), "category_id": cat, "tv_archive": 0,
        })
        if i % 4 == 0:  # autre qualité de la même chaîne
            out.append({
                "num": 500 + i, "name": "FR| " + name + " SD", "stream_type": "live", "stream_id": 3000 + i,
                "stream_icon": logo_url(i), "epg_channel_id": epg, "added": str(NOW - 86400 * 30), "category_id": cat,
            })
    return out


def vod_streams():
    out = []
    sid = 20000
    for cat, names in MOVIES.items():
        for j, n in enumerate(names):
            sid += 1
            out.append({
                "num": sid - 20000, "name": n, "stream_type": "movie", "stream_id": sid, "stream_icon": poster(n),
                "rating": "%.1f" % (5.8 + (sid * 37 % 40) / 10.0), "added": str(NOW - (sid % 50) * 86400 - j * 3600),
                "category_id": cat, "container_extension": "mp4", "tmdb": "",
            })
    return out


def series_list():
    out = []
    sid = 40000
    for cat, names in SERIES.items():
        for j, n in enumerate(names):
            sid += 1
            out.append({
                "num": sid - 40000, "name": n, "series_id": sid, "cover": poster(n), "plot": PLOTS[sid % len(PLOTS)],
                "cast": "", "director": "", "genre": "Drame", "releaseDate": "2023-01-01", "rating": "%.1f" % (6.5 + (sid % 30) / 10.0),
                "last_modified": str(NOW - (sid % 20) * 86400), "category_id": cat, "backdrop_path": [backdrop(n)],
            })
    return out


VOD = vod_streams()
SER = series_list()
LIVE = live_streams()


def b64(s):
    return base64.b64encode(s.encode("utf-8")).decode("ascii")


PROGRAMS = ["Journal de 20h", "Téléfoot", "Koh-Lanta", "Le Grand Match", "Quotidien", "C à vous", "Des chiffres et des lettres",
            "Faites entrer l'accusé", "Ligue 1 : PSG – OM", "Top Chef", "Questions pour un champion", "Envoyé spécial",
            "Le Magazine de la santé", "N'oubliez pas les paroles", "Capital", "Zone interdite", "Les Feux de l'amour"]


def short_epg(stream_id):
    r = random.Random(stream_id)
    start = NOW - r.randint(10, 50) * 60
    out = []
    for k in range(6):
        dur = r.choice([30, 45, 60, 90, 120]) * 60
        out.append({"title": b64(r.choice(PROGRAMS)), "description": b64(PLOTS[k % len(PLOTS)]),
                    "start_timestamp": start, "stop_timestamp": start + dur})
        start += dur
    return {"epg_listings": out}


def xmltv():
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv>']
    for i, (cat, name, epg, _) in enumerate(CHANNELS):
        lines.append('<channel id="%s"><display-name>%s</display-name></channel>' % (epg, name.replace("&", "&amp;")))
    for i, (cat, name, epg, _) in enumerate(CHANNELS):
        r = random.Random(i)
        start = NOW - r.randint(10, 80) * 60
        for k in range(14):
            dur = r.choice([30, 45, 60, 90, 120]) * 60
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            title = r.choice(PROGRAMS).replace("&", "&amp;")
            lines.append('<programme start="%s" stop="%s" channel="%s"><title>%s</title><desc>%s</desc></programme>'
                         % (fmt(start), fmt(start + dur), epg, title, PLOTS[k % len(PLOTS)]))
            start += dur
    lines.append('</tv>')
    return "\n".join(lines).encode("utf-8")


def movie_info(vid):
    m = next((x for x in VOD if str(x["stream_id"]) == str(vid)), VOD[0])
    return {"info": {"name": m["name"], "plot": PLOTS[int(m["stream_id"]) % len(PLOTS)], "genre": "Aventure, Drame",
                     "duration": "02:%02d:00" % (int(m["stream_id"]) % 50), "releasedate": "2023-03-15", "rating": m["rating"],
                     "movie_image": m["stream_icon"], "director": "Denis Villeneuve", "cast": "Timothée Chalamet, Zendaya, Rebecca Ferguson",
                     "backdrop_path": [backdrop(m["name"])]},
            "movie_data": {"stream_id": m["stream_id"], "name": m["name"], "container_extension": "mp4"}}


def series_info(sid):
    s = next((x for x in SER if str(x["series_id"]) == str(sid)), SER[0])
    eps = {}
    eid = int(sid) * 100
    for season in (1, 2, 3):
        lst = []
        for e in range(1, 9):
            eid += 1
            lst.append({"id": str(eid), "episode_num": e, "season": season, "title": "Épisode %d" % e, "container_extension": "mp4",
                        "info": {"duration": "00:%02d:00" % (42 + e), "plot": PLOTS[e % len(PLOTS)],
                                 "movie_image": "https://picsum.photos/seed/%s-%d-%d/640/360" % (slug(s["name"]), season, e)}})
        eps[str(season)] = lst
    return {"info": {"name": s["name"], "cover": s["cover"], "plot": s["plot"], "genre": "Drame, Aventure", "releaseDate": "2023-01-15",
                     "rating": s["rating"], "cast": "Pedro Pascal, Bella Ramsey", "backdrop_path": s["backdrop_path"]},
            "seasons": [], "episodes": eps}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s\n" % (fmt % args))

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p.startswith("/logo/"):
            i = int(p.split("/")[-1].split(".")[0])
            return self.send(200, logo_png(i), "image/png")
        if p.endswith("/player_api.php"):
            a = q.get("action")
            if a is None:
                return self.send(200, {
                    "user_info": {"username": "demo", "password": "demo", "auth": 1, "status": "Active",
                                  "exp_date": str(NOW + 86400 * 200), "max_connections": "2", "active_cons": "0"},
                    "server_info": {"url": "10.0.2.2", "port": str(PORT), "timezone": "Europe/Paris"}})
            if a == "get_live_categories":
                return self.send(200, [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in LIVE_CATS])
            if a == "get_vod_categories":
                return self.send(200, [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in VOD_CATS])
            if a == "get_series_categories":
                return self.send(200, [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in SERIES_CATS])
            if a == "get_live_streams":
                return self.send(200, LIVE)
            if a == "get_vod_streams":
                return self.send(200, VOD)
            if a == "get_series":
                return self.send(200, SER)
            if a == "get_vod_info":
                return self.send(200, movie_info(q.get("vod_id")))
            if a == "get_series_info":
                return self.send(200, series_info(q.get("series_id")))
            if a in ("get_short_epg", "get_simple_data_table"):
                return self.send(200, short_epg(int(q.get("stream_id", "0"))))
            return self.send(200, [])
        if p.endswith("/xmltv.php"):
            return self.send(200, xmltv(), "application/xml")
        if p.startswith("/live/") or p.startswith("/movie/") or p.startswith("/series/"):
            if VIDEO and os.path.exists(VIDEO):
                data = open(VIDEO, "rb").read()
                ctype = "video/mp2t" if p.endswith(".ts") or p.startswith("/live/") else "video/mp2t"
                return self.send(200, data, ctype)
            return self.send(404, b"no video", "text/plain")
        return self.send(404, b"not found", "text/plain")


if __name__ == "__main__":
    print("Faux serveur Xtream sur le port", PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
