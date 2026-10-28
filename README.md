# Xtream Player 2.0

Lecteur IPTV pour Xiaomi TV Stick et Android TV, compatible avec les codes Xtream. Appli à installer à la main, sans passer par le Play Store.

## Nouveautés de la 2.0

- **Plusieurs sources** : une source active plus d'autres en réserve. Pour chacune : statut, date d'expiration, écrans autorisés, connexions en cours, nombre de chaînes, films et séries, part du catalogue reconnue par TMDB. Avec Rafraîchir, Modifier, Masquer des catégories, Supprimer et Ajouter.
- **Catalogue enregistré sur le stick** : les listes sont lues en flux et rangées dans une petite base locale, ce qui tient même avec des centaines de milliers de films sur 1 Go de RAM. L'appli s'ouvre ensuite instantanément et se met à jour toute seule selon la fréquence choisie.
- **Réglages IPTV** : fréquence de mise à jour, guide TV, préchargement du guide (programme en cours affiché dans la liste des chaînes), fusion des catégories similaires, format du direct et User Agent.
- **Interface** : liste Revoir des films terminés, et liste Reprendre des films commencés.
- **Protection multi-écran** : avant chaque lecture, l'appli demande au serveur combien d'écrans utilisent le compte, toutes applis et tous appareils confondus, et prévient si la limite est atteinte.
- **Lecteur** : taille des sous-titres, enchaînement des épisodes (désactivé, fin de saison ou toute la série), durée du tampon et tampon max.
- **Fiche film** : résumé, note, genre, réalisation, distribution, reprise, favori, téléchargement.
- **Téléchargements** : films et épisodes gardés hors ligne sur le stick, avec suivi et suppression dans Réglages.
- **Saisie depuis le téléphone** : le stick affiche un QR code et un code à 4 chiffres. Tu remplis les champs (source, clé TMDB, User Agent) au clavier du téléphone.
- **TMDB** : avec une clé gratuite, les affiches et résumés manquants sont complétés.

## Obtenir l'APK

### Option A : GitHub (sans rien installer sur ton PC)

1. Crée un dépôt sur github.com (il peut être privé) et envoie-y tout le dossier du projet.
2. Va dans l'onglet **Actions** du dépôt. La compilation se lance toute seule (environ 5 minutes).
3. Ouvre la compilation terminée et télécharge l'artefact **XtreamPlayer-apk**. C'est un zip qui contient `app-release.apk`.

Si la compilation échoue, copie le message d'erreur et envoie-le-moi.

### Option B : Android Studio

1. Installe Android Studio et ouvre le dossier du projet.
2. Laisse-le télécharger ce qu'il demande.
3. Menu **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

## Installer sur le Xiaomi TV Stick

1. Active les options développeur : **Paramètres > Préférences de l'appareil > À propos**, puis appuie 7 fois sur **Build**.
2. Installe **Send Files to TV** sur le stick et sur ton téléphone.
3. Envoie l'APK depuis le téléphone, ouvre-le sur le stick et autorise l'installation.
4. Au premier lancement, choisis **Saisir depuis le téléphone** pour entrer ta source.

La première mise à jour des listes peut prendre une à deux minutes avec un gros catalogue.

## Commandes à la télécommande

- Dans les listes : appui long sur OK pour ajouter ou retirer des favoris. Sur un épisode, l'appui long ouvre le menu (lire depuis le début, télécharger, marquer comme vu).
- En direct : Haut et Bas pour changer de chaîne, OK pour le guide TV.
- Films et séries : Gauche et Droite pour reculer ou avancer de 10 secondes, OK pour les commandes.
- Dans les réglages : Gauche et Droite règlent les curseurs, Retour revient au menu.

## Si ça ne marche pas

- **Identifiants refusés ou erreur 403** : essaie un autre User Agent dans Réglages > Réglages IPTV (IPTVSmartersPro est souvent accepté).
- **Les chaînes tournent dans le vide** : change le format du direct en HLS.
- **La saisie téléphone ne s'ouvre pas** : le téléphone doit être sur le même Wi-Fi que le stick.
- **Le téléchargement échoue** : beaucoup de fournisseurs comptent un téléchargement comme un écran. Arrête la lecture en cours avant de télécharger.
