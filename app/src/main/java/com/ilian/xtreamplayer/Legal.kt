package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan

/**
 * Volet légal : conditions d'utilisation, confidentialité, crédits et licences, marques.
 * L'acceptation des conditions est demandée au premier lancement (et à chaque nouvelle version des conditions).
 */
object Legal {
    /** À augmenter quand les conditions changent : elles seront présentées à nouveau. */
    private const val VERSION = "1"
    const val CREATOR = "ilianmer"

    class Doc(val title: String, val body: String)

    fun accepted(): Boolean = try {
        Db.kvGet("legal_accepted") == VERSION
    } catch (_: Exception) {
        true
    }

    private fun accept() {
        try {
            Db.kvPut("legal_accepted", VERSION)
        } catch (_: Exception) {
        }
    }

    private var asking = false

    /** Fenêtre d'acceptation (si besoin), puis [then]. */
    fun ask(act: Activity, then: () -> Unit) {
        if (accepted()) {
            then()
            return
        }
        if (asking) return
        asking = true
        Sheet.message(
            act, "Bienvenue dans Xtream Player",
            "Xtream Player est un lecteur : il ne fournit aucune chaîne, aucun film ni aucune série. " +
                "Tu y ajoutes ta propre source et tu restes responsable de son utilisation, dans le respect des droits d'auteur.\n\n" +
                "Tes identifiants, profils et favoris restent sur ton appareil. Aucun compte, aucune publicité, aucun pistage.",
            primary = "J'accepte",
            onPrimary = {
                asking = false
                accept()
                then()
            },
            secondary = "Lire les conditions",
            onSecondary = {
                asking = false
                act.startActivity(Intent(act, LegalActivity::class.java))
            },
            cancelable = false
        ) ?: run { asking = false }
    }

    val DOCS = listOf(
        Doc(
            "Conditions d'utilisation", """
## Objet
Xtream Player (« l'application ») est un lecteur multimédia pour Android TV, conçu et édité par $CREATOR. Il lit des flux audio et vidéo depuis une source que tu configures toi-même (identifiants Xtream Codes). Ces conditions encadrent son utilisation.

## Acceptation
En utilisant l'application, tu acceptes ces conditions. Si tu ne les acceptes pas, n'utilise pas l'application.

## Aucun contenu fourni
L'application est un simple lecteur. Elle ne fournit, n'héberge, ne vend et ne distribue aucune chaîne, aucun film ni aucune série, et n'est liée à aucun fournisseur de contenus. Tout ce qui s'affiche provient uniquement de la source que tu ajoutes.

## Ta responsabilité
- Tu es seul responsable des sources que tu ajoutes et de l'usage que tu en fais.
- Tu t'engages à n'utiliser que des sources pour lesquelles tu disposes des droits ou des autorisations nécessaires, dans le respect des lois, notamment sur le droit d'auteur et les droits voisins.
- Tu gardes tes identifiants confidentiels.

## Informations complémentaires
Le guide des programmes, les affiches, les notes, les classements, les scores et les cotes viennent de services tiers (voir « Confidentialité » et « Crédits et licences »). Ils sont donnés à titre indicatif, sans garantie d'exactitude ni de disponibilité. Les cotes sportives sont purement informatives : l'application ne propose aucun pari.

## Disponibilité et garanties
L'application est fournie « en l'état », sans garantie d'aucune sorte. Son fonctionnement dépend de ta source, de ta connexion et de services tiers qui peuvent changer ou s'interrompre. Aucune garantie de continuité, de qualité ou d'adéquation à un usage particulier n'est donnée.

## Limitation de responsabilité
Dans les limites permises par la loi, l'éditeur ne pourra être tenu responsable d'aucun dommage direct ou indirect lié à l'utilisation de l'application, à l'impossibilité de l'utiliser, ou aux contenus accessibles par les sources que tu ajoutes.

## Mises à jour
L'application peut proposer des mises à jour. Ces conditions peuvent évoluer : la nouvelle version te sera présentée et s'appliquera dès que tu l'acceptes.

## Propriété
L'application, son nom, son design, ses avatars et son code appartiennent à leur auteur, $CREATOR, sous réserve des composants libres listés dans « Crédits et licences ». Toute reproduction ou revente sans autorisation est interdite.

## Droit applicable
Ces conditions sont soumises au droit français.
""".trimIndent()
        ),
        Doc(
            "Confidentialité", """
## En bref
Pas de compte, pas de publicité, pas de pistage. Tes données restent sur ton appareil : l'éditeur n'a ni serveur ni base de données, et ne reçoit rien.

## Ce qui reste sur ton appareil
- Tes sources : adresse du serveur, identifiant et mot de passe.
- Tes profils, avatars, favoris, reprises de lecture, équipes suivies, réglages et disposition de l'accueil.
- Les listes de chaînes, films et séries et le guide des programmes, gardés pour aller plus vite.

## Services contactés
Pour fonctionner, l'application contacte directement :
- Ton fournisseur (la source que tu ajoutes) : il reçoit tes identifiants et les demandes de lecture, comme avec n'importe quel lecteur.
- xmltvfr.fr : téléchargement du guide TV.
- TMDB, TVmaze et iTunes : titres recherchés pour trouver affiches, résumés, notes et tendances (TMDB seulement si tu ajoutes ta clé).
- ESPN : scores, calendriers et cotes des matchs.
- The Odds API : cotes de plusieurs bookmakers, seulement si tu ajoutes ta clé.
- GitHub : vérification et téléchargement des mises à jour.
Comme pour toute connexion à Internet, ces services voient l'adresse IP de ton réseau. Chacun applique sa propre politique de confidentialité.

## Tes données, tes choix
Supprime un profil, une source ou l'application pour effacer de l'appareil les données correspondantes.

## Éditeur
$CREATOR
""".trimIndent()
        ),
        Doc(
            "Crédits et licences", """
## Création
Xtream Player est conçu et développé par $CREATOR. Les 30 avatars sont des créations originales de l'application.

## Données et images
- Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.
- Informations de séries : TVmaze (tvmaze.com), licence CC BY-SA.
- Affiches et informations de films : Apple iTunes Search API.
- Scores, calendriers et cotes : ESPN.
- Cotes de bookmakers (facultatif) : The Odds API.
- Guide des programmes : xmltvfr.fr.

## Composants libres
- AndroidX et Media3 (lecteur vidéo ExoPlayer) : Apache License 2.0, © The Android Open Source Project.
- FFmpeg (décodeurs audio de secours) : licence LGPL v2.1 ou ultérieure, ffmpeg.org ; intégration Media3 par le projet Jellyfin.
- Kotlin : Apache License 2.0, © JetBrains.
- Polices Outfit et Bebas Neue : SIL Open Font License 1.1.
""".trimIndent()
        ),
        Doc(
            "Marques et contenus", """
## Marques
Les noms, logos et marques des chaînes, compétitions, équipes, films et séries affichés appartiennent à leurs propriétaires. Ils servent uniquement à identifier les contenus de ta source. Xtream Player n'est affilié à aucun d'eux, ni approuvé par eux.

## Contenus
L'application n'héberge aucun contenu. Pour tout contenu que tu jugerais illicite, adresse-toi au fournisseur de la source concernée.

## Éditeur
Xtream Player, application créée par $CREATOR.
""".trimIndent()
        )
    )

    /** Mise en forme : « ## » titres dorés, « - » puces, paragraphes aérés. */
    fun format(act: Activity, body: String): CharSequence {
        val sb = SpannableStringBuilder()
        val accent = act.getColor(R.color.accent)
        val white = act.getColor(R.color.white)
        for (raw in body.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    val st = sb.length
                    sb.append(line.removePrefix("## "))
                    sb.setSpan(ForegroundColorSpan(accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.12f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(TypefaceSpan("sans-serif-medium"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("\n")
                }
                line.startsWith("- ") -> {
                    val st = sb.length
                    sb.append("•  ")
                    sb.setSpan(ForegroundColorSpan(accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(line.removePrefix("- ")).append("\n")
                }
                line.isBlank() -> {}
                else -> {
                    val st = sb.length
                    sb.append(line).append("\n")
                    sb.setSpan(ForegroundColorSpan(white), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }
}
