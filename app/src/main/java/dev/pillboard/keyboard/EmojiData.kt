package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Paint

/**
 * Built-in emoji set: ten tabs (recents + nine categories), searchable by English and Indonesian keywords, skin-tone
 * variants for people/gesture emoji, and a recents list kept in [Prefs]. Glyphs come from the system emoji font; any
 * emoji this device cannot draw is filtered out ([Paint.hasGlyph]) so newer emoji never show up as empty boxes.
 */
internal object EmojiData {
    class Category(val glyph: String, val name: String, val items: List<String>)
    private class Entry(val e: String, val words: String)

    private const val RECENT_KEY = "emoji_recent"
    private const val RECENT_MAX = 36

    // each line: emoji, then keywords (English + Indonesian) separated by spaces
    private const val SMILEYS = """
😀 grinning smile happy senang
😃 smiley happy joy gembira
😄 smile happy laugh tertawa
😁 grin beaming nyengir
😆 laughing satisfied ngakak
😅 sweat smile lega
🤣 rofl rolling laughing lol ngakak
😂 joy tears laugh lol tertawa
🙂 slight smile senyum
🙃 upside down terbalik
😉 wink kedip
😊 blush smile malu senyum
😇 innocent angel halo malaikat
🥰 love hearts adore sayang cinta
😍 heart eyes love suka cinta
🤩 star struck wow kagum
😘 kiss blow cium
😗 kissing cium
😚 kissing closed eyes cium
😙 kissing smiling cium
😋 yum delicious tasty enak
😛 tongue lidah
😜 wink tongue bercanda
🤪 zany crazy gila
😝 squint tongue
🤑 money mouth uang kaya
🤗 hug hugging peluk
🤭 giggle oops tutup mulut
🤫 shush quiet diam
🤔 thinking hmm mikir
🤐 zipper mouth rahasia
🤨 raised eyebrow curiga
😐 neutral datar
😑 expressionless datar
😶 no mouth speechless
😏 smirk sombong
😒 unamused bete
🙄 eye roll malas
😬 grimace awkward canggung
😮‍💨 exhale relief lega
🤥 lying pinocchio bohong
😌 relieved lega tenang
😔 pensive sedih murung
😪 sleepy ngantuk
🤤 drool ngiler
😴 sleeping tidur zzz
😷 mask sick masker sakit
🤒 thermometer fever demam
🤕 bandage hurt luka
🤢 nauseated mual
🤮 vomit muntah
🤧 sneeze bersin flu
🥵 hot panas
🥶 cold dingin
🥴 woozy pusing
😵 dizzy pusing
🤯 mind blown kaget
🤠 cowboy
🥳 party celebrate pesta
😎 cool sunglasses keren
🤓 nerd kutu buku
🧐 monocle curious
😕 confused bingung
😟 worried khawatir
🙁 frown sedih
😮 open mouth wow kaget
😲 astonished kaget
😳 flushed malu
🥺 pleading please mohon
😦 frowning
😨 fearful takut
😰 anxious cold sweat cemas
😥 sad relieved sedih
😢 cry tear nangis
😭 sob crying nangis
😱 scream shocked takut
😖 confounded
😣 persevere
😞 disappointed kecewa
😓 downcast sweat
😩 weary lelah
😫 tired capek
🥱 yawn menguap bosan
😤 triumph huff kesal
😡 angry mad marah
😠 angry marah
🤬 cursing swearing marah
😈 devil smiling setan
👿 angry devil
💀 skull tengkorak mati
💩 poop kotoran
🤡 clown badut
👻 ghost hantu
👽 alien
👾 space invader game
🤖 robot
😺 cat smile kucing
😸 cat grin kucing
😹 cat joy kucing
😻 cat heart eyes kucing
😼 cat smirk kucing
🙀 cat weary kucing
😿 cat cry kucing
🙈 see no evil monkey monyet
🙉 hear no evil monkey monyet
🙊 speak no evil monkey monyet
💋 kiss mark cium
💯 hundred 100 seratus
💥 boom collision ledakan
💫 dizzy star bintang
💦 sweat drops keringat air
💨 dash wind lari angin
💤 zzz sleep tidur
"""

    private const val PEOPLE = """
👋 wave hello hi halo lambaikan
🤚 raised back hand
🖐 hand fingers splayed
✋ raised hand stop berhenti
🖖 vulcan salute
👌 ok okay oke
🤌 pinched fingers
🤏 pinching little sedikit
✌ victory peace damai
🤞 crossed fingers harapan
🤟 love you gesture
🤘 rock on metal
🤙 call me shaka
👈 point left kiri
👉 point right kanan
👆 point up atas
👇 point down bawah
☝ index up
👍 thumbs up like bagus suka
👎 thumbs down dislike jelek
✊ fist raised kepalan
👊 punch fist bogem
🤛 left fist
🤜 right fist
👏 clap applause tepuk tangan
🙌 raised hands hore
👐 open hands
🤲 palms up together
🤝 handshake salaman
🙏 pray thanks please terima kasih doa mohon
✍ writing hand menulis
💅 nail polish kuku
🤳 selfie
💪 muscle strong kuat otot
🦾 mechanical arm
👀 eyes mata lihat
👁 eye mata
👅 tongue lidah
👄 mouth bibir mulut
🧠 brain otak
🦷 tooth gigi
👶 baby bayi
🧒 child anak
👦 boy anak laki
👧 girl anak perempuan
🧑 person orang
👨 man pria laki
👩 woman wanita perempuan
🧓 older person lansia
👴 old man kakek
👵 old woman nenek
🙋 raising hand tanya
🙇 bowing minta maaf
🤦 facepalm malu
🤷 shrug entah
💁 information desk
🙅 no gesture tidak
🙆 ok gesture
👮 police polisi
👷 construction worker pekerja
🕵 detective
💂 guard
🥷 ninja
🧑‍⚕️ doctor dokter
🧑‍🎓 student pelajar mahasiswa
🧑‍🏫 teacher guru
🧑‍🍳 cook chef koki
🧑‍💻 technologist programmer coder
🧑‍🎤 singer penyanyi
🧑‍🚀 astronaut
🧑‍🚒 firefighter pemadam
👸 princess putri
🤴 prince pangeran
🦸 superhero
🧙 mage wizard penyihir
🧛 vampire
🧜 merperson
🧚 fairy peri
🧞 genie
🧟 zombie
💆 massage pijat
🚶 walking jalan
🏃 running lari
💃 dancer dance menari
🕺 man dancing menari
🧘 lotus yoga meditasi
👪 family keluarga
👫 couple pasangan
💏 kiss couple
💑 couple heart
"""

    private const val ANIMALS = """
🐶 dog puppy anjing
🐱 cat kucing
🐭 mouse tikus
🐹 hamster
🐰 rabbit kelinci
🦊 fox rubah
🐻 bear beruang
🐼 panda
🐨 koala
🐯 tiger harimau
🦁 lion singa
🐮 cow sapi
🐷 pig babi
🐸 frog katak kodok
🐵 monkey monyet
🐔 chicken ayam
🐧 penguin
🐦 bird burung
🐤 chick anak ayam
🦆 duck bebek
🦅 eagle elang
🦉 owl burung hantu
🦇 bat kelelawar
🐺 wolf serigala
🐗 boar babi hutan
🐴 horse kuda
🦄 unicorn
🐝 bee lebah
🐛 bug ulat
🦋 butterfly kupu-kupu
🐌 snail siput
🐞 ladybug
🐜 ant semut
🦟 mosquito nyamuk
🕷 spider laba-laba
🦂 scorpion kalajengking
🐢 turtle kura-kura
🐍 snake ular
🦎 lizard kadal
🐙 octopus gurita
🦑 squid cumi
🦐 shrimp udang
🦀 crab kepiting
🐡 blowfish ikan
🐠 tropical fish ikan
🐟 fish ikan
🐬 dolphin lumba-lumba
🐳 whale paus
🦈 shark hiu
🐊 crocodile buaya
🐅 tiger harimau
🐆 leopard macan
🦓 zebra
🦍 gorilla
🐘 elephant gajah
🦏 rhino badak
🐪 camel unta
🦒 giraffe jerapah
🐃 buffalo kerbau
🐂 ox sapi
🐎 horse racing kuda
🐕 dog anjing
🐈 cat kucing
🐓 rooster ayam jago
🦃 turkey kalkun
🦚 peacock merak
🦜 parrot burung beo
🦢 swan angsa
🕊 dove merpati damai
🐇 rabbit kelinci
🐿 chipmunk tupai
🦔 hedgehog landak
🌵 cactus kaktus
🎄 christmas tree pohon natal
🌲 evergreen pohon
🌳 tree pohon
🌴 palm tree pohon kelapa
🌱 seedling tunas
🌿 herb daun
🍀 four leaf clover keberuntungan
🍁 maple leaf daun
🍂 fallen leaf daun
🍃 leaf wind daun
🌾 rice sheaf padi
💐 bouquet bunga
🌷 tulip bunga
🌹 rose mawar bunga
🌺 hibiscus bunga
🌸 cherry blossom sakura bunga
🌼 blossom bunga
🌻 sunflower bunga matahari
🌞 sun face matahari
🌝 full moon face bulan
🌛 moon face
🌙 crescent moon bulan
⭐ star bintang
🌟 glowing star bintang
✨ sparkles kilau
⚡ lightning petir
🔥 fire api panas
🌈 rainbow pelangi
☀ sun matahari
⛅ partly sunny berawan
☁ cloud awan
🌧 rain hujan
⛈ thunderstorm badai
❄ snowflake salju
☃ snowman
🌊 wave ombak laut
💧 droplet tetes air
🌍 earth bumi
🌎 earth americas bumi
🌏 earth asia bumi
🌋 volcano gunung berapi
🏔 mountain gunung
🏝 island pulau
"""

    private const val FOOD = """
🍏 green apple apel
🍎 red apple apel
🍐 pear pir
🍊 orange jeruk
🍋 lemon
🍌 banana pisang
🍉 watermelon semangka
🍇 grapes anggur
🍓 strawberry stroberi
🫐 blueberries
🍈 melon
🍒 cherries ceri
🍑 peach persik
🥭 mango mangga
🍍 pineapple nanas
🥥 coconut kelapa
🥝 kiwi
🍅 tomato tomat
🍆 eggplant terong
🥑 avocado alpukat
🥦 broccoli brokoli
🥬 leafy green sayur
🥒 cucumber timun
🌶 hot pepper cabai pedas
🌽 corn jagung
🥕 carrot wortel
🧄 garlic bawang putih
🧅 onion bawang
🥔 potato kentang
🍠 sweet potato ubi
🥐 croissant
🍞 bread roti
🥖 baguette
🧀 cheese keju
🥚 egg telur
🍳 cooking fried egg telur goreng
🥞 pancakes
🥓 bacon
🥩 steak daging
🍗 poultry leg ayam goreng
🍖 meat bone daging
🌭 hot dog
🍔 hamburger burger
🍟 fries kentang goreng
🍕 pizza
🥪 sandwich
🌮 taco
🌯 burrito
🥙 stuffed flatbread kebab
🍝 spaghetti pasta
🍜 ramen noodles mie
🍲 stew pot
🍛 curry rice kari nasi
🍣 sushi
🍱 bento
🥟 dumpling pangsit
🍤 fried shrimp udang
🍙 rice ball onigiri nasi
🍚 rice nasi
🍘 rice cracker
🍥 fish cake
🥠 fortune cookie
🍢 oden sate
🍡 dango
🍧 shaved ice es
🍨 ice cream es krim
🍦 soft serve es krim
🥧 pie
🧁 cupcake
🍰 cake kue
🎂 birthday cake kue ulang tahun
🍮 custard pudding puding
🍭 lollipop permen
🍬 candy permen
🍫 chocolate cokelat
🍿 popcorn
🍩 donut
🍪 cookie kue
🌰 chestnut
🥜 peanuts kacang
🍯 honey madu
🥛 milk susu
🍼 baby bottle susu botol
☕ coffee kopi
🍵 tea teh
🧃 juice box jus
🥤 cup straw minuman
🧋 bubble tea boba
🍶 sake
🍺 beer bir
🍻 beers cheers
🥂 clinking glasses
🍷 wine
🥃 whiskey
🍸 cocktail
🍹 tropical drink
🧊 ice es batu
🥄 spoon sendok
🍴 fork knife garpu pisau
🍽 plate piring
🥢 chopsticks sumpit
"""

    private const val ACTIVITIES = """
⚽ soccer football sepak bola
🏀 basketball
🏈 american football
⚾ baseball
🥎 softball
🎾 tennis
🏐 volleyball voli
🏉 rugby
🎱 pool billiards 8 ball
🏓 ping pong tenis meja
🏸 badminton bulu tangkis
🥅 goal net gawang
🏒 ice hockey
⛳ golf
🏹 bow arrow panah
🎣 fishing memancing
🤿 diving
🥊 boxing tinju
🥋 martial arts karate
🎽 running shirt
🛹 skateboard
🛼 roller skate
⛸ ice skate
🎿 ski
🏂 snowboard
🏋 weight lifting angkat beban gym
🤸 cartwheel
🏊 swimming renang
🚴 biking sepeda
🧗 climbing panjat
🏇 horse racing
🏆 trophy piala juara
🥇 gold medal juara
🥈 silver medal
🥉 bronze medal
🏅 medal
🎖 military medal
🎗 reminder ribbon
🎫 ticket tiket
🎟 admission tickets
🎪 circus tent
🎭 performing arts teater
🎨 art palette seni lukis
🎬 clapper board film
🎤 microphone mic karaoke
🎧 headphone musik
🎼 musical score
🎹 piano
🥁 drum drum
🎷 saxophone
🎺 trumpet
🎸 guitar gitar
🎻 violin biola
🎲 dice dadu
♟ chess catur
🎯 dart bullseye target
🎳 bowling
🎮 video game gim
🕹 joystick
🧩 puzzle
🎰 slot machine
🎉 party popper pesta selamat
🎊 confetti ball pesta
🎈 balloon balon
🎁 gift present hadiah
🎀 ribbon pita
🎃 jack o lantern halloween
🎆 fireworks kembang api
🎇 sparkler
🧨 firecracker petasan
"""

    private const val TRAVEL = """
🚗 car mobil
🚕 taxi taksi
🚙 suv
🚌 bus
🚎 trolleybus
🏎 racing car balap
🚓 police car polisi
🚑 ambulance
🚒 fire engine pemadam
🚚 truck truk
🚜 tractor traktor
🛵 scooter motor vespa
🏍 motorcycle motor
🚲 bicycle sepeda
🛴 kick scooter
🚂 locomotive kereta
🚆 train kereta
🚇 metro subway
🚊 tram
🚄 bullet train kereta cepat
✈ airplane pesawat
🛫 departure berangkat
🛬 arrival tiba
🚁 helicopter
🚀 rocket roket
🛸 ufo piring terbang
🛶 canoe perahu
⛵ sailboat kapal layar
🚤 speedboat
🛳 passenger ship kapal
🚢 ship kapal
⚓ anchor jangkar
⛽ fuel pump bensin
🚧 construction
🚦 traffic light lampu lalu lintas
🗺 world map peta
🗽 statue of liberty
🗼 tokyo tower
🏰 castle kastil
🏯 japanese castle
🎡 ferris wheel
🎢 roller coaster
🏖 beach pantai
🏜 desert gurun
⛰ mountain gunung
🏕 camping
🏠 house rumah
🏡 house garden rumah
🏢 office building kantor
🏥 hospital rumah sakit
🏦 bank
🏨 hotel
🏪 convenience store minimarket
🏫 school sekolah
🏭 factory pabrik
⛪ church gereja
🕌 mosque masjid
🛕 hindu temple pura
🕍 synagogue
⛩ shinto shrine
🌅 sunrise matahari terbit
🌄 sunrise mountains
🌇 sunset matahari terbenam
🌃 night stars malam
🌉 bridge at night jembatan
🌌 milky way galaksi
🎑 moon viewing
⌚ watch jam tangan
⏰ alarm clock jam alarm
⏳ hourglass jam pasir
🕐 one o clock jam
"""

    private const val OBJECTS = """
📱 phone mobile hp ponsel
💻 laptop komputer
🖥 desktop computer
⌨ keyboard papan ketik
🖱 mouse computer
💾 floppy disk
💿 cd
📷 camera kamera
📸 camera flash
🎥 movie camera video
📺 tv televisi
📻 radio
🔋 battery baterai
🔌 plug colokan
💡 light bulb lampu ide
🔦 flashlight senter
🕯 candle lilin
🧯 fire extinguisher
💰 money bag uang
💵 dollar uang
💶 euro
💳 credit card kartu kredit
💎 gem diamond berlian
🔧 wrench kunci inggris
🔨 hammer palu
⚙ gear gigi
🧰 toolbox
🔩 nut bolt baut
🧲 magnet magnet
🔫 water pistol
💣 bomb bom
🔪 knife pisau
🛡 shield perisai
🔮 crystal ball
💊 pill obat
💉 syringe suntik
🩺 stethoscope
🌡 thermometer
🧬 dna
🔬 microscope mikroskop
🔭 telescope teleskop
📡 satellite antenna
🧹 broom sapu
🧺 basket keranjang
🧻 toilet paper tisu
🚽 toilet
🚿 shower mandi
🛁 bathtub
🧼 soap sabun
🧽 sponge
🛒 shopping cart belanja
🚪 door pintu
🛏 bed kasur tempat tidur
🛋 couch sofa
🔑 key kunci
🔒 locked gembok
🔓 unlocked
📦 package paket
📫 mailbox
📧 email surel
✉ envelope amplop surat
📝 memo note catatan
📄 page dokumen
📊 bar chart grafik
📈 chart up naik
📉 chart down turun
📌 pushpin
📎 paperclip klip
📏 ruler penggaris
✂ scissors gunting
📚 books buku
📖 open book buku
📓 notebook
🖊 pen pena
✏ pencil pensil
🖌 paintbrush kuas
🔍 magnifier search cari
🔎 magnifier right
📅 calendar kalender
🗑 wastebasket sampah
👓 glasses kacamata
🕶 sunglasses
👕 t shirt kaos baju
👖 jeans celana
🧣 scarf
🧤 gloves
🧥 coat jaket
👗 dress gaun
👟 sneaker sepatu
👠 high heel
👑 crown mahkota
🎩 top hat
🎓 graduation cap wisuda
👜 handbag tas
🎒 backpack ransel tas
🧳 luggage koper
☂ umbrella payung
💍 ring cincin
💄 lipstick
🔔 bell lonceng
🎵 music note musik
🎶 notes musik
📢 loudspeaker
📣 megaphone
"""

    private const val SYMBOLS = """
❤ red heart love cinta hati
🧡 orange heart hati
💛 yellow heart hati
💚 green heart hati
💙 blue heart hati
💜 purple heart hati
🖤 black heart hati
🤍 white heart hati
🤎 brown heart hati
💔 broken heart patah hati
❣ heart exclamation
💕 two hearts hati
💞 revolving hearts
💓 beating heart
💗 growing heart
💖 sparkling heart
💘 heart arrow cupid
💝 heart ribbon
💟 heart decoration
♥ heart suit
💢 anger marah
💬 speech bubble chat pesan
💭 thought bubble pikiran
🗯 anger bubble
✅ check mark button benar centang
✔ check mark centang
☑ checked box
❌ cross mark salah silang
❎ cross button
⭕ hollow circle
⛔ no entry dilarang
🚫 prohibited dilarang
⚠ warning peringatan
❗ exclamation seru
❓ question tanya
❕ white exclamation
❔ white question
‼ double exclamation
⁉ exclamation question
➕ plus tambah
➖ minus kurang
✖ multiply kali
➗ divide bagi
♾ infinity
💲 dollar sign
™ trade mark
© copyright
® registered
➡ right arrow kanan
⬅ left arrow kiri
⬆ up arrow atas
⬇ down arrow bawah
↗ up right
↘ down right
↙ down left
↖ up left
↔ left right
↕ up down
🔄 counterclockwise arrows ulang
🔁 repeat ulang
🔀 shuffle acak
▶ play putar
⏸ pause jeda
⏹ stop
⏺ record
⏩ fast forward
⏪ rewind
🔼 up button
🔽 down button
🔊 speaker loud keras
🔇 muted senyap
🔔 bell
🔕 no bell
🔴 red circle merah
🟠 orange circle
🟡 yellow circle kuning
🟢 green circle hijau
🔵 blue circle biru
🟣 purple circle ungu
⚫ black circle hitam
⚪ white circle putih
🟥 red square
🟧 orange square
🟨 yellow square
🟩 green square
🟦 blue square
🟪 purple square
⬛ black square
⬜ white square
🔶 orange diamond
🔷 blue diamond
🔺 red triangle
🔻 red triangle down
💠 diamond dot
🔘 radio button
🔳 white square button
🆗 ok button
🆕 new baru
🆒 cool
🆓 free gratis
🆘 sos tolong
🔞 no under 18 dewasa
♻ recycle daur ulang
⚜ fleur de lis
🔱 trident
〽 part alternation mark
☮ peace
☯ yin yang
✝ cross
☪ star crescent
🕉 om
✡ star of david
🔯 star dotted
♈ aries
♉ taurus
♊ gemini
♋ cancer
♌ leo
♍ virgo
♎ libra
♏ scorpio
♐ sagittarius
♑ capricorn
♒ aquarius
♓ pisces
"""

    private const val FLAGS = """
🏳 white flag
🏴 black flag
🏁 chequered flag finish
🚩 triangular flag
🏳️‍🌈 rainbow flag pride
🏴‍☠️ pirate flag bajak laut
🇮🇩 indonesia flag bendera
🇲🇾 malaysia flag bendera
🇸🇬 singapore flag bendera
🇹🇭 thailand flag bendera
🇻🇳 vietnam flag bendera
🇵🇭 philippines filipina flag bendera
🇯🇵 japan jepang flag bendera
🇰🇷 south korea korea flag bendera
🇨🇳 china flag bendera
🇮🇳 india flag bendera
🇸🇦 saudi arabia flag bendera
🇦🇪 uae emirates flag bendera
🇹🇷 turkey turki flag bendera
🇪🇬 egypt mesir flag bendera
🇿🇦 south africa afrika selatan flag bendera
🇳🇬 nigeria flag bendera
🇧🇷 brazil flag bendera
🇦🇷 argentina flag bendera
🇲🇽 mexico meksiko flag bendera
🇺🇸 usa united states amerika flag bendera
🇨🇦 canada kanada flag bendera
🇬🇧 uk united kingdom inggris flag bendera
🇫🇷 france perancis flag bendera
🇩🇪 germany jerman flag bendera
🇮🇹 italy italia flag bendera
🇪🇸 spain spanyol flag bendera
🇵🇹 portugal flag bendera
🇳🇱 netherlands belanda flag bendera
🇷🇺 russia rusia flag bendera
🇺🇦 ukraine flag bendera
🇦🇺 australia flag bendera
🇳🇿 new zealand selandia baru flag bendera
🇵🇸 palestine palestina flag bendera
🇧🇩 bangladesh flag bendera
🇵🇰 pakistan flag bendera
"""

    private val sources = listOf(
        Triple("😀", "Smileys", SMILEYS), Triple("👋", "People", PEOPLE), Triple("🐻", "Animals & nature", ANIMALS),
        Triple("🍔", "Food & drink", FOOD), Triple("⚽", "Activities", ACTIVITIES), Triple("🚗", "Travel & places", TRAVEL),
        Triple("💡", "Objects", OBJECTS), Triple("\u2764\uFE0F", "Symbols", SYMBOLS), Triple("\uD83C\uDFF3\uFE0F", "Flags", FLAGS),
    )

    /** Base code points that take Fitzpatrick skin-tone modifiers. */
    private val TONED = "👋🤚🖐✋🖖👌🤌🤏✌🤞🤟🤘🤙👈👉👆👇☝👍👎✊👊🤛🤜👏🙌👐🤲🙏✍💅🤳💪👶🧒👦👧🧑👨👩🧓👴👵🙋🙇🤦🤷💁🙅🙆👮👷🕵💂🥷👸🤴🦸🧙🧚🧛🧜🧞💆🚶🏃💃🕺🧘🏋🤸🏊🚴🧗🏇"
        .let { s -> buildSet { var i = 0; while (i < s.length) { val cp = s.codePointAt(i); add(cp); i += Character.charCount(cp) } } }
    private val TONES = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

    private var built: List<Category>? = null
    private var index: List<Entry> = emptyList()

    private fun parse(block: String, paint: Paint, into: MutableList<Entry>): List<String> {
        val out = ArrayList<String>()
        for (line in block.trim().lines()) {
            val sp = line.indexOf(' ')
            val raw = if (sp < 0) line else line.substring(0, sp)
            if (raw.isEmpty() || !paint.hasGlyph(raw)) continue
            // a lone code point (heart, sun, hand...) must ask for emoji presentation or it may draw as a flat text glyph
            val e = if (raw.codePointCount(0, raw.length) == 1) raw + "\uFE0F" else raw
            out.add(e)
            into.add(Entry(e, if (sp < 0) "" else line.substring(sp + 1).lowercase()))
        }
        return out
    }

    /** Categories without the recents tab; built once (the glyph check is the only cost). */
    @Synchronized fun categories(): List<Category> {
        built?.let { return it }
        val paint = Paint()
        val all = ArrayList<Entry>(1200)
        val cats = sources.map { (g, n, block) -> Category(g, n, parse(block, paint, all)) }
        index = all
        built = cats
        return cats
    }

    fun recents(c: Context): List<String> =
        Prefs.str(c, RECENT_KEY, "").split(' ').filter { it.isNotEmpty() }

    fun addRecent(c: Context, e: String) {
        val list = ArrayList(recents(c))
        list.remove(e); list.add(0, e)
        Prefs.setStr(c, RECENT_KEY, list.take(RECENT_MAX).joinToString(" "))
    }

    fun clearRecents(c: Context) = Prefs.setStr(c, RECENT_KEY, "")

    /** Matches by keyword prefix first (best), then substring; empty query = nothing. */
    fun search(q: String, limit: Int = 40): List<String> {
        categories()
        val s = q.trim().lowercase()
        if (s.isEmpty()) return emptyList()
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        for (en in index) {
            val words = en.words.split(' ')
            when {
                words.any { it.startsWith(s) } -> starts.add(en.e)
                en.words.contains(s) -> contains.add(en.e)
            }
        }
        return (starts + contains).distinct().take(limit)
    }

    /** Skin-tone variants of [e] (default first), or empty if it takes none. */
    fun tones(e: String): List<String> {
        if (e.isEmpty()) return emptyList()
        val cp = e.codePointAt(0)
        if (cp !in TONED) return emptyList()
        val first = String(Character.toChars(cp))
        val rest = e.substring(first.length).removePrefix("\uFE0F")      // keep a ZWJ tail (profession), drop the VS16
        val paint = Paint()
        val v = TONES.map { first + it + rest }.filter { paint.hasGlyph(it) }
        return if (v.isEmpty()) emptyList() else listOf(e) + v
    }
}
