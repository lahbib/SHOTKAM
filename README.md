# ShootCam

Transforme un téléphone Android (monté sur le fusil) en caméra de tir :
la vidéo tourne en **tampon circulaire**, et à chaque tir l'appli sauvegarde
**N s avant + M s après** (30 s / 20 s par défaut, réglables).

## Fonctionnement

| Élément | Détail |
|---|---|
| Tampon | Vidéo H.264 + son AAC déjà encodés, gardés en RAM (~40 Mo pour 30 s en 1080p) |
| Détection du tir | Pic d'accélération (recul) et/ou crête sonore (détonation) — 4 modes |
| Mise en joue | Rotation brusque (gyroscope) puis stabilisation ≥ 250 ms → arme la caméra |
| Tirs rapprochés | Un tir pendant l'après-tir prolonge le même clip (nom : `_3tirs`) |
| Sortie | MP4 dans **Films/ShootCam** (galerie), sans ré-encodage |
| Arrière-plan | Service de premier plan : continue écran éteint, notification « Sauver / Arrêter » |

### Modes d'armement
- **Sur mise en joue** (défaut) : capteurs seuls → caméra lancée au mouvement d'épaulé.
  Le « avant-tir » commence donc à la mise en joue. Désarmement après inactivité (120 s).
- **Permanent** : caméra toujours active, avant-tir complet garanti, plus gourmand.

## Calibrage (à faire une fois au stand)
1. Démarrer, monter le téléphone, tirer.
2. Lire à l'écran les crêtes `Accél`, `Gyro`, `Son`.
3. Réglages → seuil de recul ≈ 70 % du pic observé ; seuil mise en joue un peu sous le pic gyro d'un épaulé.
4. En battue (tirs voisins) : mode **Recul ET détonation**.

## Compiler l'APK
- **Android Studio** : ouvrir le dossier → Run.
- **GitHub** : pousser le repo → Actions → artefact `ShootCam-apk`.
- **GitLab** : `.gitlab-ci.yml` fourni → artefact du job `build-apk`.
- Local : `./gradlew assembleRelease` (JDK 17 + SDK Android 35). APK signé clé debug, installable directement.

Android 10+ (minSdk 29). Permissions : caméra, micro, notifications.

## Limites connues
- Découpe à l'image clé près (1 s) : l'avant-tir peut faire jusqu'à +1 s.
- Certains constructeurs (Xiaomi, Huawei, Samsung) tuent les services en arrière-plan :
  désactiver l'optimisation batterie pour ShootCam.
- 4K + 60 i/s non supportés par tous les capteurs (repli automatique sur la meilleure taille dispo).
