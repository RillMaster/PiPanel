-- Recherche de la sphère via le réseau sans fil
local core = peripheral.find("energyStorageCore")

-- Recherche d'un écran connecté (soit directement, soit en face)
local monitor = peripheral.find("monitor")

if not core then
  print("Erreur : Impossible de trouver la sphère Draconic à distance !")
  print("Verifiez que le modem sans fil est bien place et active.")
  return
end

if monitor then
  monitor.setTextScale(1)
end

-- Fonction pour formater les grands nombres (RF / OP)
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

-- Boucle principale
while true do
  local tier = core.getTier and core.getTier() or "Inconnu"
  local energyStored = core.getEnergyStored and core.getEnergyStored() or 0
  local maxEnergy = core.getMaxEnergyStored and core.getMaxEnergyStored() or 0
  local transfer = core.getTransferPerTick and core.getTransferPerTick() or 0

  local percentage = 0
  if maxEnergy > 0 then
    percentage = (energyStored / maxEnergy) * 100
  end

  local target = monitor or term
  target.clear()
  target.setCursorPos(1, 1)

  target.write("=== DRACONIC ENERGY CORE ===")
  target.setCursorPos(1, 3)
  target.write("Tier de la Sphere : " .. tostring(tier))
  
  target.setCursorPos(1, 5)
  target.write("Stockage :")
  target.setCursorPos(1, 6)
  target.write(formatEnergy(energyStored) .. " / " .. formatEnergy(maxEnergy))
  
  target.setCursorPos(1, 8)
  target.write(string.format("Remplissage : %.2f%%", percentage))

  target.setCursorPos(1, 10)
  target.write("Flux I/O : " .. formatEnergy(transfer) .. " /t")

  sleep(1)
end
