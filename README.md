# Xtream Player

Lecteur IPTV pour Android TV, pensé pour le Xiaomi TV Stick et sa télécommande. Il fonctionne avec un abonnement IPTV compatible Xtream Codes (adresse du serveur, identifiant et mot de passe).

L'appli ne fournit aucune chaîne ni aucun film : elle lit ceux de ton propre abonnement.

Créée par ilianmer.

## Installer

1. Sur le stick, ouvre Paramètres > Préférences de l'appareil > À propos et appuie 7 fois sur « Build » pour activer les options développeur, puis autorise l'installation d'applis de sources inconnues.
2. Récupère l'APK de la dernière version à cette adresse :
   https://github.com/ilianmer/xtream-player/releases/latest/download/XtreamPlayer.apk
   Le plus simple : l'appli Downloader sur le stick (tape l'adresse), ou Send Files to TV depuis ton téléphone.
3. Ouvre le fichier et installe.
4. Au premier lancement, accepte les conditions d'utilisation puis ajoute ta source. Tu peux remplir les champs au clavier du téléphone : le stick affiche un QR code et un code à 4 chiffres.

Avec un gros catalogue, la première mise à jour des listes prend une à deux minutes. Ensuite, l'appli s'ouvre tout de suite.

## Mises à jour

L'appli prévient au lancement quand une nouvelle version est publiée et l'installe en un clic. Pour vérifier à la main : Réglages > Interface > Rechercher une mise à jour. Les réglages, les profils et les favoris sont conservés.

Les versions vont de V4.01 à V4.20, puis V5.01, V5.02, etc. Les nouveautés de chaque version sont dans l'onglet Releases.

## Accueil

L'accueil est fait de widgets, comme sur un iPhone. Avec « Personnaliser l'accueil » (l'icône en haut à droite, ou dans les Réglages), tu ajoutes, retires et déplaces les widgets, et tu choisis leur taille : petit, moyen ou grand. Chaque page remplit l'écran.

Widgets disponibles : À la une, Scores en direct, Reprendre la lecture, Top 10 des films, Top 10 des séries, Chaînes favorites, Ce soir à la TV, Matchs du jour, Cotes en direct, Nouveautés, Ma liste, Horloge, et une catégorie de films ou de séries au choix.

## Direct

- Les chaînes s'affichent en liste ou en guide des programmes, avec l'aperçu vidéo de la chaîne survolée et la fiche du programme en cours et du suivant.
- Les catégories sont en pastilles, toujours entières, avec une petite flèche quand il en reste d'un côté. Un appui long sur une catégorie l'affiche à chaque ouverture du Direct.
- « Ce soir » place le guide sur la soirée.
- Les touches chiffrées mènent directement à un numéro de chaîne.
- Les fausses chaînes de séparation du fournisseur (« #### CINÉMA #### »…) sont masquées.
- En lecture : reprise automatique après une coupure, format TS ou HLS, cadence de l'écran adaptée à la chaîne, volume égal d'une chaîne à l'autre.

## Sport

- Scores en direct, matchs à venir et résultats en football, basket, rugby, tennis et sports de combat. Tu choisis les compétitions et tes équipes, dont les matchs passent en premier.
- La chaîne qui diffuse un match est cherchée dans le guide de ton fournisseur et testée avant d'être lancée.
- Pendant que tu regardes une chaîne, les scores de tes équipes et des grands matchs défilent, avec une alerte à chaque but.
- Cotes : une barre de pronostic donne les chances de chaque équipe. Les étiquettes « Match serré », « Grand favori », « Exploit en cours » et « Surprise » aident à choisir quoi regarder. Un comparateur affiche les cotes de Winamax, Betclic, Unibet, PMU et NetBet.

Les cotes des bookmakers demandent une clé gratuite The Odds API (500 requêtes par mois), à coller dans Réglages > Sport. L'appli répartit ces requêtes pour qu'elles tiennent tout le mois, et le compteur est visible au même endroit. Les cotes sont indicatives : l'appli ne propose aucun pari.

## Films et séries

- Catalogue par catégories, Top 10 du jour (nouveautés, popularité, notes) et Nouveautés.
- Fiches avec résumé, note, genre, réalisation et distribution, complétées par TMDB si tu ajoutes une clé gratuite.
- Reprise là où tu t'étais arrêté, Ma liste et la liste Revoir.
- Pendant la lecture : vitesse de 0,5× à 2×, épisode suivant en un clic, enchaînement automatique des épisodes, sous-titres, pistes audio et format d'image.

## Recherche

Une seule recherche pour les chaînes, les films et les séries, avec les résultats au fil de la saisie. Tu peux aussi taper depuis le téléphone.

## Profils

Jusqu'à 5 profils par source. Chacun a son personnage (une trentaine au choix), sa couleur, ses favoris, sa reprise de lecture, ses langues de contenu, ses équipes et son accueil.

Chaque profil peut être protégé par un code à 4 chiffres : Gérer les profils, choisis le profil, puis « Code à 4 chiffres ». Le code est demandé à l'ouverture du profil, au lancement de l'appli et après 10 minutes passées ailleurs. En cas d'oubli, le mot de passe de la source permet de le retirer.

## Sources et réglages

- Plusieurs sources IPTV, avec pour chacune le statut, la date d'expiration, le nombre d'écrans autorisés et les connexions en cours.
- Mise à jour automatique du catalogue. Guide TV du fournisseur ou d'une adresse XMLTV de ton choix.
- Catégories affichées et leur ordre, fusion des catégories qui se ressemblent, langues du contenu.
- Protection multi-écran : avant chaque lecture, l'appli vérifie combien d'écrans utilisent déjà le compte et prévient si la limite est atteinte.
- Son Dolby vers l'ampli, taille des sous-titres, tampon de lecture, User Agent.
- Mentions légales : conditions d'utilisation, confidentialité, crédits et licences, marques et contenus.

## À la télécommande

- OK ouvre. Un appui long ajoute aux favoris ou ouvre les options.
- Direct : Haut et Bas changent de chaîne, les chiffres tapent un numéro. Un premier OK affiche le programme, un second ouvre les options (audio, sous-titres, qualité, format d'image).
- Films et séries : Gauche et Droite reculent ou avancent de 10 secondes. OK affiche les commandes, avec Vitesse, Options et, pour une série, Épisode suivant.
- Retour ferme une fenêtre ou revient à l'écran précédent.

## Si ça ne marche pas

- Identifiants refusés ou erreur 403 : essaie un autre User Agent dans Réglages > Réglages IPTV.
- Une chaîne tourne dans le vide : passe le format du direct en HLS, au même endroit.
- La saisie depuis le téléphone ne s'ouvre pas : le téléphone doit être sur le même Wi-Fi que le stick.
- Plus de cotes : vérifie la clé et le compteur dans Réglages > Sport.

## Compiler

Le projet se compile avec Android Studio, ou avec `./gradlew assembleRelease` et un JDK 17. Les versions publiées sont compilées et signées automatiquement par GitHub Actions.

## Crédits

Scores et calendriers : ESPN. Fiches et affiches : TMDB. Cotes : The Odds API. Polices Outfit et Bebas Neue sous licence SIL Open Font License (dossier `licences`).
