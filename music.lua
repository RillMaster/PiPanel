-- navidrome_player.lua

-- Lecteur de musique Navidrome pour ComputerCraft (CC: Tweaked)

-- Surveille l'etat de lecture publie par le proxy (choisi depuis la page web)

-- et joue automatiquement le morceau selectionne.

--

-- Necessite : un speaker attache a l'ordinateur.

-- ================== CONFIGURATION A MODIFIER ==================

local PROXY_URL = "http://10.0.0.8:5000"

local POLL_SECONDS = 1.5

-- ================================================================

local dfpwm = require("cc.audio.dfpwm")

local speaker = peripheral.find("speaker")

if not speaker then

  error("Aucun speaker trouve. Attache un haut-parleur a cet ordinateur.")

end

local function getJSON(path)

  local resp = http.get(PROXY_URL .. path)

  if not resp then

    return nil

  end

  local body = resp.readAll()

  resp.close()

  local ok, data = pcall(textutils.unserialiseJSON, body)

  if ok then return data end

  return nil

end

-- Joue un morceau en continu ; s'arrete des que la version de l'etat change.

local function playSong(songId, title, shared, myVersion)

  print("Lecture : " .. title)

  local resp = http.get(PROXY_URL .. "/stream/" .. songId, nil, true) -- mode binaire

  if not resp then

    print("Erreur : flux audio introuvable pour " .. title)

    return

  end

  local decoder = dfpwm.make_decoder()

  while shared.version == myVersion do

    local chunk = resp.read(16 * 1024)

    if not chunk then

      break

    end

    local decoded = decoder(chunk)

    while not speaker.playAudio(decoded) do

      os.pullEvent("speaker_audio_empty")

      if shared.version ~= myVersion then break end

    end

  end

  resp.close()

  print("Lecture arretee : " .. title)

end

-- Attend que l'etat change (nouveau morceau choisi sur la page web) puis renvoie

-- true si un morceau doit etre joue.

local function waitForChange(shared)

  while true do

    sleep(POLL_SECONDS)

    local state = getJSON("/control/state")

    if state and state.version ~= shared.version then

      shared.version = state.version

      shared.id = state.id

      shared.title = state.title

      return

    end

  end

end

local function main()

  local shared = { version = -1, id = nil, title = nil }

  -- recupere l'etat initial sans forcement jouer (au demarrage du programme)

  local initial = getJSON("/control/state")

  if initial then

    shared.version = initial.version

    shared.id = initial.id

    shared.title = initial.title

  end

  print("Lecteur Navidrome pret. En attente d'un morceau depuis la page web...")

  while true do

    if shared.id then

      local myVersion = shared.version

      local myId, myTitle = shared.id, shared.title

      parallel.waitForAny(

        function() playSong(myId, myTitle, shared, myVersion) end,

        function() waitForChange(shared) end

      )

    else

      waitForChange(shared)

    end

  end

end

main()
