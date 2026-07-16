local user = "RillMaster"
local pass = "Kylian120909%40%21"
local server = "http://91.173.80.213:49153"

local url = server ..
"/rest/getSongs.view" ..
"?u="..user..
"&p="..pass..
"&v=1.16.1"..
"&c=ComputerCraft"..
"&f=json"

local response = http.get(url)

if not response then
    print("Erreur connexion Navidrome")
    return
end

local data = textutils.unserializeJSON(response.readAll())

local songs = data["subsonic-response"].songs.song

if not songs then
    print("Aucune musique trouvée")
    return
end

for i, song in ipairs(songs) do
    print(i..". "..song.title.." - "..song.artist)
end
