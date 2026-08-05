-- Recherche automatique du stockage de la sphère et du moniteur géant
local core = peripheral.find("draconic_rf_storage")
local monitor = peripheral.find("monitor")

if not core then
  print("Erreur : Aucun stockage Draconic trouve !")
  return
end

if not monitor then
  print("Erreur : Aucun ecran (monitor) detecte !")
  return
end

monitor.setTextScale(1)

-- Fonction pour formater proprement les grands nombres (RF / OP)
local function formatEnergy(value)
  if not value then return "0" end
  local suffixes = {"", "K", "M", "G", "T", "P", "E"}
  local i = 1
  while value >= 1000 and i < #suffixes do
    value = value / 1000
    i = i + 1
  end
  return string.format("%.2f %s", value, suffixes[i])
end

-- Boucle principale d'affichage en temps réel
while true do
  local energyStored = core.getEnergyStored and core.getEnergyStored() or 0
  local maxEnergy = core.getMaxEnergyStored and core.getMaxEnergyStored() or 0
  
  -- Récupération du flux si disponible via l'API, ou simulation/estimation
  local transfer = core.getTransferPerTick and core.getTransferPerTick() or 0
  
  -- Si getTransferPerTick n'existe pas ou renvoie 0, on peut interroger les autres faces/périphériques connectés si besoin, 
  -- mais en général Draconic Evolution expose le flux global ou par port.
  
  local tier = "Inconnu"
  if core.getTier then
    tier = core.getTier()
  elseif maxEnergy > 0 then
    tier = "Tier " .. tostring(math.floor(math.log10(maxEnergy) / 2))
  end

  local percentage = 0
  if maxEnergy > 0 then
    percentage = (energyStored / maxEnergy) * 100
  end

  -- Nettoyage et affichage sur le moniteur 5x5
  monitor.clear()
  monitor.setCursorPos(2, 2)
  monitor.write("=== DRACONIC ENERGY CORE ===")
  
  monitor.setCursorPos(2, 4)
  monitor.write("Sphere : " .. tostring(tier))
  
  monitor.setCursorPos(2, 6)
  monitor.write("Stockage :")
  monitor.setCursorPos(2, 7)
  monitor.write(formatEnergy(energyStored) .. " / " .. formatEnergy(maxEnergy))
  
  monitor.setCursorPos(2, 9)
  monitor.write(string.format("Remplissage : %.2f%%", percentage))

  -- Affichage des flux d'énergie (Entrée / Sortie)
  monitor.setCursorPos(2, 11)
  monitor.write("Flux Actuel : " .. formatEnergy(transfer) .. "/t")

  -- Actualisation toutes les secondes
  sleep(1)
end
