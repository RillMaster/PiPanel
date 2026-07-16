local response, err = http.get(url)

if not response then
    print("Erreur HTTP :")
    print(err)
    return
end
