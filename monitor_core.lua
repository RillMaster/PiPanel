print("Peripheriques detectes :")
for _, name in ipairs(peripheral.getNames()) do
  print("- " .. name .. " (" .. tostring(peripheral.getType(name)) .. ")")
end
