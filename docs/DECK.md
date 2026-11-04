# The mood deck ("Don't have time to browse?")

Pick a mood, scroll its posters, press OK and the title opens on its page in its app. It opens from the Mi button (see [MI-TV.md](MI-TV.md)) or a "Pick for me" tile in the app grid. The idea comes from a guide to the "I hacked my TV using Claude" reel, with the slot-machine spin replaced by a list you browse.

## Setup

1. Get a free API key at [themoviedb.org](https://www.themoviedb.org/settings/api). **Keep it out of Git.**
2. Add it to the theme config you push to the TV (not one you commit): `"deck_tmdb_key": "<your key>"`.
3. Open the deck. The first fill takes a few minutes, because it looks up links slowly on purpose.

## How it works

- **Titles:** each mood is a TMDB filter on what streams in India on Netflix, Prime Video and JioHotstar (`DeckPool.moodQueries`). To change country or apps, edit `watch_region`, `PROVIDERS` and `packageOf` there.
- **Links:** JustWatch's Android TV links, changed to open the title's page rather than play. **This is an unofficial service with no published terms that I could find, and it can change or block access without notice.** Please keep it gentle: the deck waits a second between lookups, makes at most 40 per refresh, and keeps links it already has.
- **Refresh:** about weekly, and only when the deck is opened. Failures keep the old list. The list is shuffled on every visit, with titles you have not opened lately first.
- **Memory:** posters load only while the deck is open, and it closes itself as soon as it leaves the screen.

## Credit

Uses the TMDB API but is not endorsed or certified by TMDB. Where-to-watch data: JustWatch. Posters and backdrops belong to their studios, and none are stored in this repo.

Tested on one TV: MiTV-AXSO0, India. Netflix profile screens did not appear in testing, and Prime Video shows its profile screen first and then continues to the title.
