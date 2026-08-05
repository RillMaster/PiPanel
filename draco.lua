local core = peripheral.find("draconic_rf_storage")
local monitor = peripheral.find("monitor")

if not core or not monitor then
  print("Erreur : Peripherique ou ecran introuvable !")
  return
end

-- Taille du texte (1 ou 1.5 selon vos préférences)
monitor.setTextScale(1)

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

-- Fonction pour dessiner une barre de progression en couleur
local function drawProgressBar(x, y, width, percentage)
  monitor.setCursorPos(x, y)
  monitor.setTextColour(colors.lightGray)
  monitor.write("[")
  
  local filledLength = math.floor((width - 2) * (percentage / 100))
  for i = 1, width - 2 do
    monitor.setCursorPos(x + i, y)
    if i <= filledLength then
      monitor.setTextColour(colors.lime) -- Vert pour la partie remplie
      monitor.write("#")
    else
      monitor.setTextColour(colors.gray) -- Gris pour le vide
      monitor.write("-")
    end
  end
  
  monitor.setCursorPos(x + width - 1, y)
  monitor.setTextColour(colors.lightGray)
  monitor.write("]")
end

while true do
  local energyStored = core.getEnergyStored and core.getEnergyStored() or 0
  local maxEnergy = core.getMaxEnergyStored and core.getMaxEnergyStored() or 0
  local transfer = core.getTransferPerTick and core.getTransferPerTick() or 0
  
  local tier = core.getTier and core.getTier() or "Tier 5"
  if type(tier) == "number" then tier = "Tier " .. tier end

  local percentage = 0
  if maxEnergy > 0 then
    percentage = (energyStored / maxEnergy) * 100
  end

  monitor.clear()

  -- En-tête stylé en Jaune
  monitor.setCursorPos(3, 2)
  monitor.setTextColour(colors.yellow)
  monitor.write("+----------------------------------+")
  monitor.setCursorPos(3, 3)
  monitor.write("|      DRACONIC ENERGY CORE        |")
  monitor.setCursorPos(3, 4)
  monitor.write("+----------------------------------+")

  -- Tier de la sphère en Cyan
  monitor.setCursorPos(3, 6)
  monitor.setTextColour(colors.cyan)
  monitor.write(" Capacite : ")
  monitor.setTextColour(colors.white)
  monitor.write(tostring(tier))

  -- Énergie stockée en Cyan/Blanc
  monitor.setCursorPos(3, 8)
  monitor.setTextColour(colors.cyan)
  monitor.write(" Stockage Actuel :")
  
  monitor.setCursorPos(5, 9)
  monitor.setTextColour(colors.orange)
  monitor.write(formatEnergy(energyStored))
  monitor.setTextColour(colors.lightGray)
  monitor.write(" / ")
  monitor.setTextColour(colors.lightBlue)
  monitor.write(formatEnergy(maxEnergy))

  -- Barre de progression et pourcentage
  drawProgressBar(5, 11, 32, percentage)
  monitor.setCursorPos(39, 11)
  monitor.setTextColour(colors.lime)
  monitor.write(string.format("%.1f%%", percentage))

  -- Flux I/O (Entrée / Sortie) avec couleur selon l'activité
  monitor.setCursorPos(3, 14)
  monitor.setTextColour(colors.cyan)
  monitor.write(" Flux I/O     : ")
  
  if transfer >= 0 then
    monitor.setTextColour(colors.green)
    monitor.write("+" .. formatEnergy(transfer) .. "/t (Charge)")
  else
    monitor.setTextColour(colors.red)
    monitor.write(formatEnergy(transfer) .. "/t (Decharge)")
  end

  -- Ligne de bas de page en Jaune
  monitor.setCursorPos(3, 16)
  monitor.setTextColour(colors.yellow)
  monitor.write("+----------------------------------+")

  sleep(1)
end
