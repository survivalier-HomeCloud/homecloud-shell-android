# HomeCloud Shell pour Android

Client Android de **HomeCloud Shell** : un vrai terminal sur votre serveur HomeCloud, dans le bac à sable de votre compte,
avec plusieurs onglets, des sessions qui restent ouvertes en arrière-plan et le lien d'accès des services que vous lancez.

- Java, **aucune dépendance** (uniquement le framework Android), interface construite en code.
- Android 8.0 ou plus (minSdk 26), compilé avec le SDK 34.
- Parle le même protocole que le client en ligne de commande `homecloud-shell` (HTTP upgrade sur `/shell` de HomeCloud Web,
  puis trames `[type][longueur][données]`).

## Compiler et installer

1. Ouvrez le dossier dans **Android Studio** (Koala ou plus récent, JDK 17 embarqué) et laissez la synchronisation Gradle se faire
   (le SDK Android 34 sera proposé au téléchargement si besoin), puis **Run**.
2. Ou en ligne de commande, avec le SDK Android installé (`ANDROID_HOME`) :
   ```
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Utilisation

1. **Ajouter un serveur** : adresse de HomeCloud Web (ex. `192.168.1.78`), port (8680 par défaut), compte HomeCloud.
   Le mot de passe est facultatif : s'il est mémorisé, il est chiffré par le Keystore Android (AES-256-GCM, clé non exportable).
2. Touchez le serveur pour ouvrir une session. HomeCloud Shell doit être installé et démarré sur le serveur
   (`homecloud-shelld install`).
3. **Onglets** : `+` ouvre une autre session (5 par compte au maximum), appui long sur un onglet pour la fermer.
4. **Quitter l'écran du terminal ne ferme pas la session** : un service au premier plan (notification « Tout fermer ») garde les
   connexions ouvertes. Les programmes lancés dans le shell vivent donc tant que la session existe.

### Services lancés dans le shell

Quand un programme du shell se met à écouter sur un port, le terminal affiche une ligne encadrée avec son lien :

```
┌───────────────────────────────────────────────────────────────┐
│ Port 8000 ouvert : http://192.168.1.78:8680/port/nheard/8000/ │
└───────────────────────────────────────────────────────────────┘
```

Touchez un lien (n'importe quelle URL `http(s)://` affichée dans le terminal) pour l'ouvrir ou le copier.
Menu ⋮ → **Ports ouverts** : liste de ceux annoncés dans la session. Nécessite un `homecloud-shelld` récent (annonce des ports).

### Terminal

| Geste / touche | Effet |
| --- | --- |
| Toucher | focus + clavier ; clic souris pour les programmes qui la demandent (htop, mc...) |
| Glisser / lancer | remonter l'historique (5000 lignes) ; dans `less`, `man`, `vim` : flèches ou molette |
| Pincer | taille du texte (mémorisée) |
| Appui long puis glisser | sélection, **Copier** / **Coller** |
| Barre de touches | Esc, Tab, Ctrl, Alt (un appui = prochaine touche), flèches (répétition), ^C, ^D, `\| / - ~`, Home, End, PgUp, PgDn |
| Clavier physique | flèches, F1-F12, Ctrl+lettre, Alt+touche, Tab, Échap... |

Émulation xterm-256color : couleurs 16 / 256 / truecolor, gras, italique, souligné, écran alternatif (vim, htop, nano), région
de défilement, caractères larges (CJK, emoji), tracé DEC (mc, dialog), collage protégé (bracketed paste), souris SGR.

## Sécurité

- **Le protocole n'est pas chiffré** (comme le client en ligne de commande) : le mot de passe et le terminal circulent en clair
  entre le téléphone et HomeCloud Web. Utilisez-le sur votre réseau local ou à travers un VPN (WireGuard, Tailscale...),
  pas sur Internet en direct.
- Un mot de passe mémorisé n'est lisible que par l'application sur cet appareil ; « Oublier le mot de passe » dans la fiche du serveur
  le supprime. Les sessions gardent le mot de passe en mémoire seulement (pour reconnecter et ouvrir des onglets).
- Les liens `/port/...` sont servis par HomeCloud Web, qui demande votre connexion HomeCloud.

## Structure

```
app/src/main/java/com/homecloud/shell/
  Protocol.java          trames du protocole
  ShellConnection.java   connexion : HTTP upgrade, authentification, lecture, écriture sur un thread dédié, keepalive
  TerminalEmulator.java  émulateur VT100/xterm (sans dépendance Android, testé)
  TerminalView.java      rendu, gestes, sélection, clavier, IME, liens
  ExtraKeysBar.java      barre de touches
  Session.java           une session : connexion + écran + état (vit hors des écrans)
  SessionManager.java    liste des sessions du processus
  ShellService.java      service au premier plan + notification
  MainActivity.java      serveurs, sessions actives, réglages
  TerminalActivity.java  onglets + terminal
  Profile*, SecretBox    serveurs enregistrés, chiffrement Keystore
tests/
  run.sh                 tests unitaires (émulateur, protocole, connexion contre un faux serveur), JDK seul
  e2e/run.sh             client Java contre un vrai homecloud-shelld (root, python3)
```

La police est **JetBrains Mono** (licence OFL, `app/src/main/assets/fonts/OFL.txt`), choisie pour des glyphes de cadre et de blocs
cohérents.

## Réglages

- **Garder le processeur actif** : maintient un wake lock tant que des sessions existent (évite la coupure en veille, coûte de la batterie).
- Menu ⋮ du terminal : écran toujours allumé, taille du texte, copier tout l'écran.
- Un keepalive est envoyé toutes les 25 s pour que les routeurs ne coupent pas une session inactive.

## Limites connues

- Les caractères combinants (accent séparé de sa lettre) sont ignorés ; les lettres précomposées s'affichent normalement.
- Pas de réflux du texte au redimensionnement (les lignes sont tronquées / complétées).
- Si l'application est tuée par le système, les connexions (et donc les shells) sont fermées.
