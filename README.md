# Nuvio + IPTV, édition française style TiviMate (fork)

> **Ce dépôt est un fork de [homelessbrian/NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV).** L'app, sa TV en direct, son Sport, son Multiview et tout le reste sont le travail de [homelessbrian](https://github.com/homelessbrian) et des contributeurs qu'il cite (dont [ghbarker](https://github.com/ghbarker)), eux-mêmes basés sur [NuvioTV](https://github.com/NuvioMedia/NuvioTV). Tout le mérite de l'app leur revient.
>
> Ce fork ajoute par-dessus un guide plus proche de TiviMate, des corrections du replay et une traduction française plus complète. Les mises à jour du projet d'origine y sont intégrées régulièrement. Le code reste sous la même licence, [GPL-3.0](LICENSE).
>
> Les problèmes liés aux changements de ce fork ne concernent pas l'auteur d'origine : merci de ne pas les lui signaler. Le code Downloader, les versions publiées et les liens (Discord, Ko-fi…) de la présentation d'origine plus bas concernent l'app d'origine, pas ce fork.

## Changements de ce fork

### Guide style TiviMate
Avec l'option **Guide style TiviMate** (paramètres de la TV en direct, avec la taille du guide) :
- **Grille :** colonne des chaînes sans case, noms en gras, chaîne en lecture en bleu avec ▶. Cases avec le titre seul et des coins légèrement arrondis, ligne sélectionnée un peu plus claire. Date et heure en bleu au-dessus des chaînes, ligne « maintenant » fine et bleue. Marge de gauche réduite, grille jusqu'au bord droit de l'écran.
- **Groupes :** une fois ouverte, la liste des groupes occupe toute la hauteur à gauche, et l'en-tête se décale à côté.
- **En-tête :** grand aperçu, ligne de la chaîne, titre, horaire avec une petite barre de progression et le temps restant, description sur plusieurs lignes.
- **Bandeau d'infos du lecteur :** fond sombre uni dont la transparence suit le réglage existant, sans liseré blanc, étiquettes grises. Les informations et les boutons ne changent pas.

### Guide (tous styles)
- **Émissions qui se chevauchent dans le guide du fournisseur :** la plus ancienne s'arrête au début de la suivante, et une même émission listée deux fois n'apparaît plus qu'une fois. Les cases ne s'empilent plus les unes sur les autres.

### Replay
- **Écran d'attente au lancement d'un replay :** en plein écran, le logo de la chaîne (son nom si elle n'en a pas) et « Chargement du replay » s'affichent jusqu'à la première image, au lieu de la dernière image du direct figée sous un petit « Chargement… ». Ça vaut pour un replay choisi dans le guide, « Regarder depuis le début » et un retour en arrière depuis le direct.
- **Même écran pour un saut vers une partie pas encore chargée :**
  - **Saut hors de la copie sauvegardée sur la box** (le replay est redemandé au fournisseur) : l'image du moment est gardée, figée et assombrie, sous le logo et « Chargement du replay ». Si elle ne peut pas être gardée (chaîne protégée, image noire), l'écran est noir.
  - **Saut dans un replay HLS au-delà de ce qui est en mémoire** : le même écran, en transparence sur la dernière image, si rien n'apparaît au bout de 0,6 s.
  - Les sauts dans la partie déjà sauvegardée restent instantanés, sans écran.
- **Plus d'à-coup après un saut hors de la copie** : l'écran d'attente ne laisse plus voir une demi-seconde de vidéo avant un nouveau « Chargement… ». Le lecteur ne relit la liste des morceaux de la copie que toutes les 10 s. Lancé dès le premier morceau (2 s), il jouait ce morceau depuis la minute ronde (les liens de replay commencent à la minute), puis attendait sa relecture suivante, et seulement alors sautait au moment demandé.
  - La copie démarre maintenant directement au moment demandé, une fois qu'elle en contient 13 s de plus. L'attente dure 10 s au plus : avec un fournisseur lent, le replay démarre le plus près possible de ce moment.
  - L'écran d'attente reste jusqu'à ce que la vidéo joue, pas seulement jusqu'à sa première image.
  - Même chose au lancement d'un replay : la vidéo ne s'arrête plus après ses 2 premières secondes. En contrepartie, la première image arrive un peu plus tard, le temps de sauvegarder ces 13 s.
- **Saut dans la partie la plus récente de la copie** (pas encore relue par le lecteur) : la copie est relue à partir de ce point, sous l'image figée et le logo, au lieu d'une image figée jusqu'à 10 s.
- **Saut pendant qu'une copie se prépare** : si le point visé y est déjà, ou le sera bientôt, la copie démarre là, au lieu d'être jetée et redemandée au fournisseur. Choisir à nouveau le même replay ne relance plus son chargement.
- **Retour dans l'app pendant un replay** : un saut fait entre-temps est gardé, un replay en pause reste en pause, et la touche Accueil pendant un chargement ne lance plus le son derrière l'écran d'accueil.
- **Barre de progression pendant un chargement** : elle reste sur le point visé. Avant, elle sautait, et le titre pouvait passer à l'émission suivante.
- **Saut de moins d'une minute** sur un replay lu directement chez le fournisseur : il n'était pas pris en compte, le replay repart maintenant au bon endroit.
- **Replay choisi pendant que le fuseau horaire du fournisseur est recherché** : un saut ne l'annule plus, et il ne repart plus en fond si l'on quitte le plein écran entre-temps.
- **Retour au début (⏮) :** sur les chaînes avec replay, le bandeau du lecteur plein écran a un bouton ⏮. Dans un replay ou un enregistrement, il revient au début. En direct, il relance l'émission en cours depuis son début.
- **Reculer depuis le direct :** en direct ou en pause sur une chaîne avec replay, ⏪, ⟲30 et la barre de progression reculent aussi au-delà de ce que garde la pause. Une fois la touche relâchée, l'émission reprend par le replay à partir de ce moment. Avancer jusqu'au direct, sur une émission encore à l'antenne, ramène au direct.
- **Démarrage en 2 s environ** (TF1 : 1,8 s au lieu de 9,2 s) :
  - l'app attend moins longtemps avant de demander le replay au fournisseur, et redemande 2 s après un refus ;
  - avec **Préférer HLS pour le replay** désactivé, le replay est demandé en TS, même sur une chaîne en m3u8 ;
  - avec l'option activée, un replay HLS qui n'affiche rien passe au TS au bout de 6 s au lieu de 15 ;
  - le premier morceau est coupé au début d'une image, même quand le flux ne le signale pas.
- **Copie supprimée en quittant la TV en direct** (retour à l'accueil, ou ouverture du lecteur de Nuvio) : avant, la sauvegarde continuait jusqu'à la fin de l'émission, à environ 35 Mo par minute, et la copie restait sur la box jusqu'à la chaîne suivante ou au redémarrage de l'app.
- **Sauvegarde qui reprend après une coupure :** un replay coupé en cours de téléchargement redemande la suite du même fichier, au lieu de s'arrêter là. Si le fournisseur ne sait pas reprendre en cours de fichier, la partie déjà sauvegardée est sautée et la sauvegarde continue.
- **Sauts dans la partie déjà sauvegardée** sans recharger tout le flux.
- **Fournisseur qui ralentit fortement** après un démarrage rapide : nouvelle connexion, qui reprend au même endroit.
- **Enchaîner les replays** ne coupe plus la nouvelle sauvegarde (les démarrages de 30 à 60 s ont disparu), et la lecture gèle moins près de la fin de la partie sauvegardée.
- **Diagnostic :** le rapport de démarrage s'écrit aussi dans les journaux système (`adb logcat -s NuvioReport`), avec la vitesse de sauvegarde toutes les 30 s. Il indique aussi quand le fournisseur a répondu, quand le premier morceau était prêt et s'il a été coupé au début d'une image.

### Traduction française
- **Textes écrits directement dans le code** (guide, lecteur, Sport, Enregistrements, Calendrier, Multiview, menus) : affichés en français quand l'appareil est en français. Le temps restant s'affiche par exemple « 1h 23min restantes ».
- **Titres en majuscules et menus terminés par « … » :** traduits eux aussi. Ça couvre les catégories du menu d'une chaîne (REGARDER, CETTE ÉMISSION, ENREGISTRER…), les sections des Enregistrements, de Sport et du Calendrier, et les menus comme « Renommer la chaîne… ».
- **Phrases avec un nom, un nombre ou une heure :** traduites en entier, dans l'ordre des mots du français. Par exemple : Enregistrement de « Journal » programmé (20:30), ou : 3 chaînes copiées dans Sport. Ça concerne le menu d'une chaîne, l'écran Enregistrements, la programmation d'un enregistrement, les messages après « Enregistrer » et l'enregistrement d'un match.
- **Heures des enregistrements** au format de l'appareil : 20:30 plutôt que 8:30 PM.
- **Fichiers de traduction :** compléments aux traductions françaises du projet d'origine.

### Compilation
- **Compilations debug locales** sans la clé de signature du projet : elles sont signées avec la clé de debug standard et s'installent sous `com.nuvio.livetv.debug`, à côté de l'app officielle.
- **Clé TMDB :** à mettre dans `local.properties` sur la ligne `TMDB_API_KEY`. Le modèle `local.example.properties` la montre, et la clé v3 est gratuite sur themoviedb.org. Sans elle, la compilation affiche un avertissement : tous les appels à TMDB échouent, et la fiche d'un film ou d'une série montre les acteurs sans photo, sans illustrations ni bandes-annonces TMDB.

---

<p align="center"><em>Présentation de l'app d'origine (en anglais) :</em></p>

<div align="center">

# Nuvio + IPTV

**Everything in Nuvio, plus everything IPTV: a TiviMate-style Live TV guide, Multiview for up to four channels at once, a Sports hub that finds every game on your own channels, a Calendar of your shows, movies and games, catch-up, recordings, your provider's movies and series, and a built-in VPN for your playback, all in one app for Google TV, Android TV and Fire TV. Your setup follows your Nuvio account, and there's a [phone app](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile) too.**

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioPlusIPTV?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioPlusIPTV/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioPlusIPTV/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioPlusIPTV/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioTV-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioTV)
[![Discord](https://img.shields.io/badge/chat-Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/NvK5JVvvm)
[![Support on Ko-fi](https://img.shields.io/badge/support-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/homelessbrian)

**Downloader code: 6532491**

[What's new](#-new-in-this-update) · [Multiview](#-multiview) · [Sports hub](#-sports-hub) · [Calendar](#-calendar) · [VPN](#-built-in-vpn) · [Sync](#-your-setup-everywhere) · [Phone app](#-on-your-phone-too) · [Download](#-installation) · [Features](#-features) · [Setup](#-getting-started) · [Remote controls](CONTROLS.md) · [Report a bug](#-reporting-bugs-and-requesting-features) · [Discord](https://discord.gg/NvK5JVvvm) · [Support](#-support-the-project)

> ### 🙌 Big thanks to [ghbarker](https://github.com/ghbarker)
> The **built-in VPN**, **Multiview** and **Stalker / Ministra portals** are his work. He wrote them and contributed them to this project. Thank you!

</div>

---

## 🆕 New in this update

- **🖥️ Multiview:** watch up to four channels or games at once, with a layout picker, live games from Sports, and the sound following the pane you pick. [More below](#-multiview).
- **📡 Stalker / Ministra portals:** add them as a source for Live TV and On Demand, next to your M3U playlists and Xtream logins.
- **📱 Add from your phone:** scan a QR code and type playlists, Xtream logins and guides on your phone instead of with the remote.
- **⚡ The guide opens straight away,** every time. Guide updates happen quietly in the background, playlists load much faster, and the app uses less memory.
- **🖼️ Seek thumbnails:** preview pictures above the seek bar in movies, shows, catch-up, recordings, sports replays and paused live TV.
- **📣 Full-time alerts** with the final score, and **Score alerts** for any single game, straight from its game page.
- **⏺️ Record options** in the player: until you stop it, until the show ends, or until a set time.
- **🌍 Live TV in more languages:** German, French, Spanish, Portuguese, Italian, Dutch, Polish, Turkish and Russian.
- **Built on Nuvio 1.2.0-beta.1,** with its subtitle fonts and styles, chapters, and fallback to the next source when one fails.

---

## 🖥️ Multiview

Watch several channels, or several games, side by side.

- **Up to four at once:** open it from the guide's top row or the **Multiview** button on the play bar, or choose **Add to Multiview** in a channel's menu.
- **Layouts with pictures:** 1 screen, 2 side by side, 2 stacked, three ways to show 3, and 4 (2 × 2, or one big + three). It opens in the layout you used last.
- **Pick channels like overlay mode:** a see-through list of your groups and channels with what's on now, plus a **Sports** tab of live games. OK on a game plays it; hold OK for its other streams or its Sports page.
- **The sound follows the pane you're on.**
- **Keeps itself going:** a pane that drops reconnects by itself, and one that won't play moves to a backup copy of the channel.

---

## 🏆 Sports hub

Sports in the side menu shows scores and schedules, and works out **which of your own channels are showing each game**. No more scrolling through sports channels to find the match.

### Your teams, front and center
- **My Sports:**
  - **The big card:** your next games in your teams' colors. Left and Right flip through them.
  - **Live & Upcoming,** then a row for every team, country, league and name you follow.
  - **Your order:** arrange the rows how you like.
- **All Events:** every game in your region's leagues plus every league and team you follow (scores for every league, wherever you are), with a live count. **Day tabs** run from a week back to a week ahead.
- **Choose Leagues:** a bar on All Events turns any league on or off.
- **Follow anything:**
  - **Teams, countries, whole leagues,** or a **name** (a player, driver or fighter) to catch any show that mentions it.
  - **One search** finds teams and leagues alike.
  - **Following a club includes its cups** and European games.
- **Where they stand:** each team's row on My Sports says where it is in its league ("2nd in Premier League").

### 🏆 Tables
- **🏆 Tables** in the Sports top bar: your leagues across the top, the table below, opened on your team. The video keeps playing beside it.
- **Football:** played, won, drawn, lost, goal difference and points, the **last five** as coloured squares, **▲ / ▼ place changes** since the last round, and coloured edges for **Champions League, Europa League and relegation** places.
- **US sports:** **Division / Conference / League** views, clinched marks (x, y, z, e) and **playoff lines** (Play-In too for the NBA).
- **Racing:** F1, NASCAR and IndyCar drivers' standings, with points behind the leader.
- **Team colours** on every row, and your team outlined in gold.
- **Team cards:** OK on a team for its record, home and away form, last three results, next game, **Watch now** (or Remind me), **Follow**, and **all its games**.
- **Players:** a team's page has a **Players** list and player profiles.
- **From a game:** OK on a game's Table panel opens the whole table with both teams marked.

### 📡 League Networks
- **Each league's own channel in one place:** NFL Network, NFL RedZone, NBA TV, MLB Network, MLB Strike Zone, NHL Network, Golf Channel, Tennis Channel, LaLiga TV and more, found in your own playlists.
- **What's on now and next** on each one, with HD, FHD and SD copies combined.
- **Pick a different channel by hand** (hold OK) if the app chose the wrong one, or go back to automatic. A network the app couldn't find still has its tile: press OK on it to choose the channel yourself.

### 200+ leagues and competitions
- **Your region first:** each region gets its own leagues by default (NFL and NBA show by default in the US and Canada). Follow any league to add it.
- **US sports:** NFL, NBA, NHL, MLB, WNBA, MLS, college football, basketball, hockey and baseball, plus CFL, UFL and G League.
- **More hockey and baseball:** AHL, ECHL, PWHL, OHL, WHL, QMJHL, USHL and Minor League Baseball, with scores, tables and game pages. Also the World Baseball Classic, the Little League World Series and Caribbean winter baseball.
- **College:** lacrosse, soccer, softball, volleyball and field hockey too.
- **Football (soccer):**
  - **The UK:** Premier League down to the National League, the FA Cup, Carabao Cup, Scottish leagues and cups, and the WSL.
  - **Europe:** the Champions League, Europa League, LaLiga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, and their cups.
  - **Further afield:** the Libertadores, Brazil, Argentina and leagues across South and Central America, the Saudi Pro League, J1, the A-League and more.
- **National teams:** follow England, France, USA and others for every game, from friendlies to the World Cup. Women's internationals have their own list.
- **Rugby:** the Six Nations, Nations Championship, Rugby Championship, World Cup, Premiership, URC, Champions Cup, Top 14 and Super Rugby. **AFL** too.
- **Cricket:** IPL, Big Bash, SA20, Major League Cricket and international Tests, ODIs and T20s.
- **Events:** UFC, PFL, F1, NASCAR, IndyCar, PGA Tour, DP World Tour, LPGA, LIV, and ATP and WTA tennis, with leaderboards, results and full fight cards. Fights are matched to your channels by both fighters' names.
- **Beyond ESPN:** from TheSportsDB, with scores about every 15 minutes on match days:
  - **non-league football:** the National League and the step below
  - **rugby league:** Super League, NRL and State of Origin
  - **cricket:** the T20 Blast
  - **ice hockey:** the Elite Ice Hockey League
  - **Gaelic games:** the National Football League
  - **netball:** the UK Netball Superleague and Super Netball
- **Wrestling and boxing schedules.** WWE and AEW shows are matched to your channels by show name.
- **Your TV guide fills the gaps:** darts, snooker, horse racing, cycling and cricket internationals are found in your own guide.

### 🧩 Sports addons
Have a sports addon installed in Nuvio, like **HighFly** or **StremVerse**? Sports now uses it too. There's nothing to set up.
- **Found automatically:** open a game and the app looks for it in your sports addons. While the page is open and nothing has shown up yet, it keeps checking, since streams usually appear close to the start.
- **Live streams in the channel list:** a game that's on shows the addons' streams in its channel list, after your own channels.
- **Replays & Highlights:** a finished game gets its own list of replays, highlights and recaps from your addons.
- **Start times** are read correctly whatever time zone the addon uses.
- **Recording and Fix Channel** work on your own playlist channels, not on an addon's streams.

### Watching
- **Watch on your channels:**
  - **Every channel showing the game,** best match first, with the playlist it comes from. HD, FHD and SD copies of a channel share one row.
  - **Your country's channels:** TV listings for each game add your own country's channels, and outside the US they come first. The channel airing the game at its start time is picked first.
  - **Labels:** each channel is marked **SHOWING IT** or **MAYBE**.
  - **Channels from:** show only one region's or language's channels (UK, US, Ireland, Canada, Australia, English, or a language), with the rest one click away. Game reminders and Watch now use them first too.
- **Switch when a channel fails:** during a live game, if a channel won't start, drops or keeps freezing, the next channel showing it takes over.
- **Fix Channel:** pick the right channel once, for one game, every game on that network, or every game for a team. It's remembered. Each network can have its own link, listed under **Your Channel Links**, and games with a link skip the guide search.
- **Video window:** hold OK on a game to watch it on the Sports page while you keep browsing. Hold Back for full screen.
- **Watch from the start** on channels with catch-up.
- **Game pages:**
  - the score by period, and the latest scoring as it happens
  - **before it starts:** form, the last meetings, match info, win chance and the table
  - **once it's on:** team stats, top performers or line-ups, and win chance as it changes
  - race and golf leaderboards, tennis matches and fight cards
  - **Up to a team** and OK for its team card
- **Scores everywhere:**
  - the score shows on Live TV's info bar when you're watching a game
  - **scoring alerts** pop up in the corner when your team scores, wherever you are in the app, and a **Full time / Final** alert shows the final score
  - **Score alerts on any game:** turn them on from a game's page, even for teams you don't follow
- **Spoiler-free:** **Hide Scores** keeps results off the screen until you're ready.

### Never miss it
- **Remind me** on any game, and automatic reminders for your teams.
- **Switch automatically:** a minute before kick-off, with 10 seconds to cancel.
- **Record a game:** pick the channel and how long to record (1 to 6 hours, for overtime and extra time). Games set to record show **REC**.
- **On every TV and phone:** your teams, leagues and Sports settings follow your Nuvio account.

---

## 📅 Calendar

**Calendar** in the side menu shows what's coming for the things you care about.

- **What's on it:** new episodes of the shows you follow, when your movies come out in theaters and at home, and games for the teams you follow in Sports.
- **Works with Trakt and Simkl:** shows in your Trakt or Simkl library and shows you're watching are added automatically, no bells needed. MDBList works too.
- **Your Country:** sets which country's TV schedule Airing Today shows, and the movie release dates you see.
- **Views:** List, Week or Month. Choose the one it opens on under **Settings → Calendar → Opens In**.
- **Filters:** All, Shows, Movies, Sports and **On my TV**.
- **Airing today** (news and talk shows can be left out) and **Recently aired** (with **Unwatched only**).
- **Following:** a poster wall of everything you follow, with an info bar. Hold OK for Watch live, Record and Remind, on games too.
- **Follow a show:** ring the bell on its page, in Live TV or in Search. New episodes get a card in Continue Watching (with **Not today** to put it away until tomorrow), and Home's **Upcoming** row shows what's coming this week.
- **Hide Spoilers:** episodes past the next one you'd watch don't show their titles or pictures.
- **On my TV:** episodes that are in your Live TV guide. Watch live, record one, record every new episode, or get a reminder, even before it's in your guide.
- **The right channel:** choose the channel for a show or a whole network once. Change or forget them in **Settings → Calendar → Saved Channels**.

---

## 🔐 Built-in VPN

Protect what you watch without installing a separate VPN app. **Settings → VPN** imports a **WireGuard** or **OpenVPN** profile from your VPN provider and uses it for your playback.

> **Thank you to [ghbarker](https://github.com/ghbarker),** who built the VPN and contributed it to Nuvio + IPTV. This feature exists because of him.

- **Choose how much it protects:**
  - **Off:** playback uses your normal connection.
  - **HTTP streams only:** protects plain HTTP streams and leaves HTTPS alone.
  - **HTTP + HTTPS streams:** protects both.
  - **Full app:** protects everything the app does (Android asks you to allow it).
- **Import your provider's file:** WireGuard configs and self-contained OpenVPN profiles (UDP or TCP). If the profile needs a username and password, the app asks for them.
- **One choice for every profile,** or a separate one for just the Nuvio profile you're using.
- **Comes with your setup:** your VPN profiles are carried to your other devices by Setup Sync and encrypted on each one.
- The same VPN is in the [phone app](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile).

---

## ☁️ Your setup, everywhere

- **Setup Sync (Nuvio Account Sync):** sign in to Nuvio and your Live TV setup (playlists, guides, favorites, hidden and renamed channels, reminders, Sports and Calendar follows, VPN profiles and settings) is restored and kept in sync on every TV and phone with that account. You can **choose what to sync**.
- **A new TV in minutes:** sign in with the QR code and everything comes back by itself.
- **Encrypted:** your saved setup, including your IPTV logins, is encrypted before it's stored. Nothing is written to Nuvio's own servers.
- **In your control:** **Settings → Live TV → Setup Sync** has Sync Now, Restore Earlier Copy, Turn Off and Delete Saved Setup.
- **Coming from Google Drive sync?** **Import from Google Drive** moves your Drive backup over in one step. Drive sync keeps working too.

---

## 📱 On your phone too

**[Nuvio + IPTV Mobile](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile)** brings Live TV, the guide, Multiview, Sports, catch-up, recordings and On Demand to Android phones and tablets. Sign in to the same Nuvio account and it uses the same setup as your TV.

---

## ✨ Features

### 📺 A proper TV guide
- **TiviMate-style guide:** channels down the side, a timeline across, and a line marking "now" (which you can hide). It fits **8 or more channels** on screen.
- **Guide Size:** Compact, Standard, Large or Extra Large. It sets the size of overlay mode too.
- **Opens straight away:** the guide is on screen as soon as Live TV opens, and updates happen quietly in the background.
- **Fast browsing:** hold Left to step back through earlier shows at double speed.
- **Two-Line Channel Names** for long names.
- **Group list:**
  - **Opening it:** it slides out with a tap of Left.
  - **Grouping:** optionally grouped by playlist.
  - **Browse by channel name** (satellite-box style).
- **Open on Last Group:** Live TV opens on the group you were last watching.
- **Info panel** with a big show poster (or just the channel logo, if you prefer), time left, the description (as much as fits), the playlist it comes from, and a live preview window.
- **Make room for more channels:** a smaller info panel, and hiding numbers, logos, names or episode numbers.
- **Badges:** catch-up icons on channels that support it, and 4K / FHD / HD / SD badges on channels you've watched.
- **Change Logo:** pick a channel's logo from your playlists, your guides, logo libraries or TMDB, search for one, or send a link from your phone.
- **Playing channel:** a red outline marks the channel you're watching (you can turn it off).

### 🎬 Overlay mode (while watching)
- Press **Left** in full screen for a see-through guide over the video: the group's channels, the highlighted channel's schedule, and the show's details.
- **Left again** switches groups. **Right** opens the channel's full schedule, with a date picker.
- **Grouped by playlist** (when you have more than one), with headings you can fold, as in the guide.

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides,** merged into one guide.
- **Stalker / Ministra portals** for Live TV and On Demand.
- **Add from your phone:** scan the QR code in **Settings → Live TV** and fill in playlists, logins and guides on your phone.
- **Xtream Codes logins:**
  - **Pick what to include:** TV channels, movies & series, or both.
  - **Stream format:** Auto, TS or HLS. M3U link playlists have it too, including Dispatcharr.
  - **Account details:** expiry date, status and connections in use, with a warning before it runs out.
- **Smart guide matching:** real listings always beat "Programming" placeholders. Fine-tune any channel with **Assign EPG**.
- **Catch-up:**
  - **Watch past shows** with a seek bar, or **restart what's on now**. Skips land where you aimed, even with providers that are slow to jump.
  - **Follow-on playback:** carries straight on into the next show.
  - **Guide time shift** and **Catch-up time shift** (up to 24 hours) for providers that are off.
- **Backup channels:** a button on the info bar lists the other copies of the channel you're watching, hidden ones included, so you can switch when one is having trouble. Copies match even when the name has a sound layout like 5.1.
- **Channel name editor** (like TiviMate): strip "USA:", "HD", "24/7" and similar from channel names. Prefixes followed by just a space ("USA ESPN") are removed too when a playlist uses them on lots of channels.

### ⏺️ Recordings
- **Record a show, or a whole series,** from the guide or overlay mode. Series recordings skip repeats and episodes you cancelled.
- **Record from the player:** until you stop it, until the show ends, or until a set time. Shows on catch-up channels are saved from catch-up after they end, which is the most reliable way.
- **Save to this device or a USB drive.** Choose what happens when space runs low: stop recording, or delete the oldest.
- **Choose the padding:** how early recordings start and how long they keep going after the show.
- **Recordings carry on with the app closed or the box in standby**, and catch-up saves wait for the connection to come back.
- **Watch while it records,** with skipping back and forward.
- **Recordings button (●)** next to the guide's ⚙.
- **Size and details** for every recording: channel, air time, length, size and where it's saved.

### 🗂️ Channel management
- **Manage visibility** and **reorder** for channels *and* groups. Move several groups at once.
- **Copy, rename and renumber** channels, **create your own groups,** and **number channels 1, 2, 3…** in each group.
- **Channel numbers from your playlist** are used when it has them.
- **Favorites** and **Recently watched,** with sorting that works everywhere. **Pick favorites** ticks many channels in a group at once.

### 🎞️ On Demand
- **What it is:** movies and series from your Xtream providers, with **categories or genres**, **Recently added**, **Search** and **Sort by**.
- **Your posters, not the provider's:** posters and details come from your own addons. Matched titles open on Nuvio's normal details page, with watched marks and the usual long-press menu.
- **📡 On Demand** in Nuvio's stream list, with quality, codec and audio badges. Choose where its tab goes, and whether auto-play may pick it.
- **Each title once:** duplicates are merged, and a picker lets you choose the version when there are several. Your provider's copies are listed best quality first, with quality tags.
- **Play Straight from My Provider:** Play skips the source list for titles you opened from On Demand.
- **Adult titles** never appear in Continue watching.

### 🔔 Watching
- **Start in Full Screen:** open the app straight into the last channel you watched.
- **Zap** with Up/Down, type a channel number, or jump to the **last channel**.
- **Pause and rewind live TV**, even on channels without catch-up.
- **Seek thumbnails:** preview pictures above the seek bar, up to 4K. On by default except on low-memory boxes, and you can switch them on or off any time under **Settings → Playback** (they may not work well on lower-end devices).
- **Reminders:** "Remind me" on any upcoming show, with a pop-up and a Watch button.
- **Player menu:** audio, **closed captions**, screen size, **picture shape**, stream info, **sleep timer**, record, find & stream, and watch from the beginning.
- **Your audio choice is remembered for each channel** (a second language, or 5.1 over stereo).
- **⏪ / ⏩ on the remote** skip 30 seconds in catch-up and paused live TV; hold to go faster.
- **On-screen clock** in any corner, if you want one.
- **Smooth playback:**
  - **Keeps the TV awake** while you watch (no screen saver).
  - **Match frame rate** (Off, On start, or On start/stop, as in Nuvio's player) and **audio passthrough** settings.
  - **Auto-reconnect,** plus automatic recovery from frozen or stuck streams, with the reason shown when a stream fails.

### 🔒 Parental controls
- **PIN locks:** a PIN that locks any group or category. Adult content locks automatically.

### 🔎 Part of Nuvio, not bolted on
- **In the side menu:** Live TV, Sports, On Demand, Recordings and the Calendar sit in Nuvio's side menu, with their settings inside Nuvio's own Settings.
- **Menu & Features:** turn pages on or off for each profile (including Live TV, Sports, On Demand and Recordings), put the side menu in your order, and put Discover in the menu or inside Search. **How the Menu Looks** adds a line above Live TV and **Shrink the Menu to Fit**.
- **Open the App On:** start straight on Home, Live TV, Sports, the Calendar, On Demand and more.
- **Home screen row:** your favorite channels and what's on now, next to Continue watching.
- **Rows on your TV's home screen:** Live Now in Sports, Favorite Channels and Recently Watched Channels for Google TV, Projectivy and similar launchers, with Wide, Tall or Square cards.
- **One search** for movies, shows, channels and On Demand. Live TV results put the best matches first.
- **Live TV in Nuvio search:**
  - **Your order:** choose which results come first.
  - **Preview behind search:** live channels play in the background while you search.
- **Find & stream in Nuvio:** see a movie in the guide, and stream it through your own addons and debrid.
- **Setup Sync** (or Google Drive sync): your setup on every TV and phone, combined correctly when you use more than one.
- **Settings in order:** Live TV, On Demand, Sports, Calendar and VPN sit together under Playback.

---

## 📥 Installation

### The quick way: Downloader code **6532491**

1. Install **Downloader** (by AFTVnews) from your TV's app store.
2. Open it, type **6532491** in the search or address box, and press **Go**.
3. It downloads the latest **universal** version (works on every device). Press **Install**, allowing installs from unknown sources when asked.

### Or pick the APK for your device

The universal version works everywhere. The device-specific ones are smaller downloads.

1. Open the **[latest release](https://github.com/homelessbrian/NuvioPlusIPTV/releases)**.
2. Download the APK for your device:

   | Device | APK |
   |---|---|
   | Most Google TV / Android TV devices | `arm64-v8a` |
   | Fire TV (all models), older or budget boxes | `armeabi-v7a` |
   | Not sure (works everywhere) | `universal` |

3. Install it with a file manager or **Downloader**, allowing installs from unknown sources when asked.

Requires **Android 7.0 or newer** (Fire OS 6 or later). It installs **alongside** the official Nuvio app.

**On a phone or tablet?** Get **[Nuvio + IPTV Mobile](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/releases)** instead.

**Updating:** updates appear **inside the app**. Each new Nuvio release is followed by a matching Nuvio + IPTV version (currently based on Nuvio 1.2.0-beta.1).

---

## 🚀 Getting started

1. Open **Settings → Live TV**.
2. Choose **Add M3U Playlist**, **Add Xtream Codes Login** or **Add Stalker / Ministra Portal** (or **Add from your phone** to type it there). For Xtream, pick **Include TV channels** and/or **Include movies & series**.
3. Optionally **Add EPG Source** for an extra TV guide.
4. Open **Live TV**, **On Demand** or **Sports** from the side menu. The first load can take a minute on big providers; a small progress bubble shows what's happening.
5. Optional, all in **Settings → Live TV**:
   - choose the page the app opens on (**Settings → Menu & Features → Open the App On**)
   - turn on the **home screen row**
   - choose where recordings are saved under **Recordings**
   - set a parental **PIN**
   - turn on **Setup Sync** (signed in to Nuvio)
   - import a WireGuard or OpenVPN profile under **Settings → VPN** if you want your playback protected
6. In **Sports**, press **✎ Follow Teams & Leagues** to pick your teams. Set your region and reminders under **Settings → Sports**.
7. Open **Calendar** and ring the bell on the shows you watch.

---

## 🎮 Remote controls

Every button for the guide, full screen, Sports, the Calendar, On Demand and Recordings: see **[CONTROLS.md](CONTROLS.md)**.

---

## 🐞 Reporting bugs and requesting features

Please search [existing issues](https://github.com/homelessbrian/NuvioPlusIPTV/issues) first. Questions and chat are welcome on **[Discord](https://discord.gg/NvK5JVvvm)**.

| | |
|---|---|
| 🐛 **Something isn't working** | [Report a bug](https://github.com/homelessbrian/NuvioPlusIPTV/issues/new?template=live_tv_bug.yml) |
| 📺 **An idea for a new feature** | [Request a feature](https://github.com/homelessbrian/NuvioPlusIPTV/issues/new?template=live_tv_feature.yml) |
| 🎨 **The look or layout could be better** | [Suggest a UI / UX improvement](https://github.com/homelessbrian/NuvioPlusIPTV/issues/new?template=live_tv_ui.yml) |

**If the app crashes,** scan the QR code on the crash screen with your phone and paste the text into your report.

**If something doesn't load** (a playlist, the guide or catch-up), open **Settings → Live TV → Help → Start-Up Report** and include it in your report. Usernames and passwords are hidden in it.

**If something in Sports or the Calendar is wrong,** say which game or show and which channel you expected.

Problems with Nuvio's movies, shows, addons or accounts that also happen in the official app belong with [Nuvio](https://github.com/NuvioMedia/NuvioTV/issues).

---

## ❓ FAQ

<details>
<summary><b>Does this include any channels or movies?</b></summary>

No. It only plays what you add yourself: your own playlists, guides, Xtream logins, addons and debrid services.
</details>

<details>
<summary><b>I don't see On Demand.</b></summary>

Turn on movies & series for your Xtream login under **Settings → Live TV → On Demand**. On Demand appears in the side menu once the first import finishes.
</details>

<details>
<summary><b>Sports shows the wrong channel for a game, or none at all.</b></summary>

Open the game and press **Fix channel**. Pick the right channel for that game, for every game on that network, or for every game a team plays. It's remembered from then on. **MAYBE** means the app wasn't sure; a channel whose guide lists the game is marked **SHOWING IT**.
</details>

<details>
<summary><b>I can't find my team or league in Sports.</b></summary>

Search for it in **✎ Follow Teams & Leagues**: search covers every league, not just your region's. If it still isn't there, follow it as a name, and any show in your TV guide with that name in the title appears on My Sports.
</details>

<details>
<summary><b>Why doesn't a league show under Tables?</b></summary>

Tables lists the leagues you follow and your teams' leagues that have a table. Cups and knockout competitions don't have one, and neither do golf, tennis or fights. Turn off **Hide Scores** under **Settings → Sports** to see tables.
</details>

<details>
<summary><b>League Networks doesn't find a channel I have, or picks the wrong one.</b></summary>

It looks for each network by its channel name. If your provider names it differently, press OK on the network's tile (or hold OK if it picked the wrong one) and choose the channel yourself. It's remembered.
</details>

<details>
<summary><b>Where did NFL or NBA go?</b></summary>

Outside the US and Canada they're no longer shown by default. Follow the league (or a team) in **✎ Follow Teams & Leagues** and its games come back.
</details>

<details>
<summary><b>A game lists too many channels from other countries.</b></summary>

Use **🌐 All regions ▾** on the game's page, or **Settings → Sports → Channels From**, to show one region's or language's channels first. Channels whose name doesn't say a region count as "other regions".
</details>

<details>
<summary><b>The Calendar can't find a show in my guide, or picks the wrong channel.</b></summary>

Choose the channel from the episode's Live TV options. It's remembered for that show (or the whole network). Change or forget saved channels in **Settings → Calendar → Saved Channels**.
</details>

<details>
<summary><b>Where do the scores come from?</b></summary>

Scores, schedules, tables, team logos and colors come from ESPN's free public feed. Minor-league and junior hockey come from HockeyTech and Minor League Baseball from MLB's stats service. Non-league football, rugby league, UK ice hockey, Gaelic games, netball, and the wrestling and boxing schedules come from TheSportsDB, and some event dates from sporting-events.org. Sports they don't cover are found in your own TV guide.
</details>

<details>
<summary><b>Some channels show "Programming" or the wrong listings.</b></summary>

Long-press the channel and choose **Assign EPG**. Press Left to search, or pick a TV guide from the buttons at the top. If every channel is off by the same amount, use **Guide time shift**.
</details>

<details>
<summary><b>Catch-up plays the wrong show (an hour or more off).</b></summary>

Run **Update now** on that playlist once, so the app learns your provider's time zone. If it's still off, set **Catch-Up Time Shift** under **Settings → Live TV → Watching**.
</details>

<details>
<summary><b>A channel looks stretched or squashed.</b></summary>

Long-press OK while it's playing and choose **Picture shape**. Try **Use real size**; the app remembers it for that channel. **Screen size** in the same menu changes how the picture fills the screen on every channel.
</details>

<details>
<summary><b>My USB drive doesn't show up for recordings.</b></summary>

Open **Save Recordings To** under **Settings → Live TV → Recordings**. Drives the app can't use are listed with the reason, usually that the drive is formatted as NTFS (read-only on most TV boxes) or isn't set up as storage yet. **Drive Not Listed?** in the same list explains what to do.
</details>

<details>
<summary><b>Will recordings work if I close the app or turn the TV off?</b></summary>

Yes, as long as the box is in standby and not fully powered off or unplugged. Shows on catch-up channels are saved from catch-up after they end, and they're retried when the connection comes back.
</details>

<details>
<summary><b>Sound cuts out or stops on Fire TV with Echo speakers.</b></summary>

Turn off **Audio passthrough** under **Settings → Live TV → Watching**.
</details>

<details>
<summary><b>How do I use the VPN?</b></summary>

Open **Settings → VPN**, import the WireGuard config or OpenVPN profile your provider gives you, and pick how much to protect: HTTP streams only, HTTP + HTTPS, or the full app. If the profile asks for a username and password, enter them when prompted.
</details>

<details>
<summary><b>Which VPN files work?</b></summary>

WireGuard configs and OpenVPN profiles that contain everything in one file (the certificates inside it, not separate files). Profiles that need extra files, scripts or interactive login aren't supported yet.
</details>

<details>
<summary><b>What does Nuvio Account Sync store, and where?</b></summary>

Your Live TV setup (playlists, guides, logins, favorites and settings), encrypted, linked to your Nuvio account and kept only to copy it between your devices. It's stored on the app's own sync service, not on Nuvio's servers. **Delete Saved Setup** removes it at any time. It works only with the official Nuvio server. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

<details>
<summary><b>My setup didn't come over to my other TV or phone.</b></summary>

Make sure both are signed in to the same Nuvio account and **Setup Sync** is on in **Settings → Live TV**, then press **Sync Now**.
</details>

<details>
<summary><b>What does Google Drive sync store, and where?</b></summary>

Your Live TV setup, in a private app folder in your own Google Drive that only this app can see. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

---

## ☕ Support the project

Nuvio + IPTV is free. If it's made your TV better and you'd like to say thanks, you can buy me a coffee on **[Ko-fi](https://ko-fi.com/homelessbrian)**. It's completely optional, and every bit helps keep updates coming.

[![Support me on Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/homelessbrian)

---

## 🙏 Credits

- **🙌 [ghbarker](https://github.com/ghbarker): a huge thank you.** He built the **built-in VPN** (WireGuard and OpenVPN playback protection), **Multiview** and **Stalker / Ministra portals**, and contributed them to this project, along with a lot of speed work. He also made the original [phone port](https://github.com/ghbarker/NuvioMobile-IPTV) that the phone app is built on.
- **[Nuvio](https://github.com/NuvioMedia/NuvioTV)** by the NuvioMedia team: Nuvio + IPTV is built on their work and follows their releases.
- Guide and overlay design inspired by **TiviMate**.
- Channel logo libraries from **[iptv-org](https://github.com/iptv-org)** and **[tv-logos](https://github.com/tv-logo/tv-logos)**.
- Sports data from **ESPN**'s public feed, **[TheSportsDB](https://www.thesportsdb.com)**, **HockeyTech**, **MLB**'s stats service and **sporting-events.org**. Show schedules from **[TVmaze](https://www.tvmaze.com)**, and pictures and logos from **[TMDB](https://www.themoviedb.org)**. Nuvio + IPTV isn't affiliated with any of them, and team and league names and logos belong to their owners.
- Built with Kotlin, Jetpack Compose, TV Material 3 and Media3 / ExoPlayer.

Nuvio + IPTV is an unofficial fork and is **not affiliated with or endorsed by NuvioMedia or TiviMate**. Please report issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. Only use sources you own or are authorized to access. See Nuvio's [legal and DMCA information](https://github.com/NuvioMedia/NuvioTV#legal--dmca), which applies here as well.

Licensed under the **GNU General Public License v3.0**, like upstream Nuvio. See [LICENSE](LICENSE).
