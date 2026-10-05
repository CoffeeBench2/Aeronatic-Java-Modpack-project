#!/usr/bin/env python3
r"""
Builds the AeroSMP Admin Handbook PDF for the owner to send to staff.

    py scripts/build_admin_handbook.py      ->  D:\MC Project\Releases\AeroSMP-Admin-Handbook.pdf

The command tables come from the registered command trees (auth / skins / guard source, permission
>= 2), checked 2026-10-06 against auth 1.13.9. When commands change, update COMMANDS below and rebuild.

🔒 This PDF is sent to people outside the owner's machine: NO IPs, hostnames, ports, panel URLs,
database names, webhook URLs or secrets. Ever. A guard at the bottom refuses to write the file if
anything IP- or URL-shaped slips in.
Built-in PDF fonts only cover Latin-1, so no emoji or symbols outside it.
"""
import os, re, sys
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

OUT = r"D:\MC Project\Releases\AeroSMP-Admin-Handbook.pdf"
COFFEE = colors.HexColor("#6F4E37")
CREAM = colors.HexColor("#F5EDE3")
INK = colors.HexColor("#2B2118")
WARN = colors.HexColor("#B3261E")

ss = getSampleStyleSheet()
H1 = ParagraphStyle("H1", parent=ss["Heading1"], textColor=COFFEE, fontSize=18, spaceBefore=6, spaceAfter=8)
H2 = ParagraphStyle("H2", parent=ss["Heading2"], textColor=COFFEE, fontSize=13, spaceBefore=10, spaceAfter=4)
BODY = ParagraphStyle("B", parent=ss["BodyText"], textColor=INK, fontSize=9.6, leading=13.2)
SMALL = ParagraphStyle("S", parent=BODY, fontSize=8.6, leading=11.2)
CELL = ParagraphStyle("C", parent=BODY, fontSize=8.6, leading=11)
CODE = ParagraphStyle("K", parent=CELL, fontName="Courier-Bold", fontSize=8.2, leading=10.5)
BULLET = ParagraphStyle("BL", parent=BODY, leftIndent=12, bulletIndent=2)
TITLE = ParagraphStyle("T", parent=ss["Title"], textColor=COFFEE, fontSize=30, leading=36, alignment=TA_CENTER)
SUB = ParagraphStyle("ST", parent=BODY, fontSize=12, alignment=TA_CENTER, textColor=INK)


def p(text, style=BODY):
    return Paragraph(text, style)


def bullets(items):
    return [Paragraph(t, BULLET, bulletText="-") for t in items]


def box(text, colour=COFFEE, bg=CREAM):
    t = Table([[Paragraph(text, BODY)]], colWidths=[170 * mm])
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), bg), ("BOX", (0, 0), (-1, -1), 1, colour),
                           ("LEFTPADDING", (0, 0), (-1, -1), 8), ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                           ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6)]))
    return t


def table(rows):
    data = [[p("<b>Command</b>", CELL), p("<b>Lvl</b>", CELL), p("<b>What it does</b>", CELL)]]
    for cmd, lvl, what in rows:
        data.append([p(cmd.replace("<", "&lt;").replace(">", "&gt;"), CODE), p(str(lvl) if lvl else "all", CELL), p(what, CELL)])
    t = Table(data, colWidths=[62 * mm, 9 * mm, 99 * mm], repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), COFFEE), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, CREAM]),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#D8C8B6")), ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4)]))
    # header row text must be white
    data[0] = [p("<font color='white'><b>%s</b></font>" % h, CELL) for h in ("Command", "Lvl", "What it does")]
    t._cellvalues[0] = data[0]
    return t


# ── content ──────────────────────────────────────────────────────────────────────────────────────────

MODERATION = [
    ("/authmod info <player>", 2, "Profile at a glance: account type, display name, playtime, flags."),
    ("/authmod player <name>  /authmod players", 2, "One player's details / everyone online with their account type."),
    ("/authmod ban <target> [reason]", 2, "Ban a player (and their IP). Shows on Discord."),
    ("/authmod unban <target>", 2, "Lift a ban."),
    ("/authmod clearban <ip>", 2, "Remove a single IP ban."),
    ("/authmod confiscate <player> [reason]", 2, "Freeze a player completely until released. Owners (level 4) can never be frozen."),
    ("/authmod confiscate list", 2, "Who is frozen right now."),
    ("/authmod release <name>", 2, "Unfreeze a confiscated player."),
    ("/afkcheck <target>", 2, "Show a player's AFK / input state (macro detector)."),
    ("/invsee <target>", 3, "Look into a player's inventory."),
    ("/invsee_echest <target>  /invsee_curios <target>", 3, "Their ender chest / curios slots."),
    ("/authmod filter add block|censor <word>", 2, "Chat filter. BLOCK refuses the message, CENSOR stars the word. Mail uses it too."),
    ("/authmod filter remove <word>  /authmod filter list", 2, "Manage the word list."),
    ("/authmod filter test <text>", 2, "See what the filter would do to a message without sending it."),
    ("/authmod duplicates", 2, "List accounts that look like the same person (alts)."),
    ("/authmod hide", 2, "Toggle yourself hidden: off the Tab list, no join message."),
    ("/authmod hud", 2, "Turn your TPS/MSPT bar off or on (it is on for staff by default)."),
    ("/aerobypass", 2, "Toggle claim/ship protection bypass so you can open, break or fix anything. TURN IT OFF after."),
]

ACCOUNTS = [
    ("/authmod queue", 2, "Names waiting for approval."),
    ("/authmod approve <playerName>", 2, "Approve a requested display name (also a button on Discord)."),
    ("/authmod reject <playerName> <reason>", 2, "Reject it with a reason the player sees."),
    ("/authmod resetpassword <name>", 2, "Clear an offline account's password so they can set a new one. Works offline."),
    ("/authmod clearips <player>", 2, "Forget their trusted IPs (forces a login next time)."),
    ("/authmod resetpos <name>", 2, "Send a stuck player back to spawn on their next join. Works offline."),
    ("/authmod resetskins <name>", 2, "Clear a broken custom skin."),
    ("/skin admin set <player> <java_name>", 2, "Give a player the skin of any Java account name. Online or offline."),
    ("/skin admin reset <player>", 2, "Back to their default skin."),
    ("/authmod setplaytime <name> <hours>", 2, "Correct someone's playtime (it drives their level)."),
    ("/authmod votereward <name> [service]", 2, "Hand out a missed vote reward by hand."),
    ("/authmod unlockadmin <name>", 2, "Clear a watchdog admin-command lock early."),
    ("/authmod transferaccount <old> <new> [confirm]", 2, "Move everything from one account to another. Without 'confirm' it only shows the plan."),
    ("/authmod freshstart <player> [confirm]", 2, "Wipe a player back to brand new. The ONLY command that deletes player data: dry run first, always."),
    ("/aeroid status <name>", 3, "Identity gate: premium/offline, Mojang link, hold, password."),
    ("/aeroid hold <name> <reason>  /aeroid release <name>", 3, "Lock an account against every login (nothing is changed, so release restores it exactly)."),
    ("/aeroid bind <name> <mojangUuid>  /aeroid unbind <name>", 3, "Link / unlink an account to a Mojang account you have checked by hand."),
    ("/aeroid move <fromUuid> <toUuid> [confirm]", 4, "Owner only: move an identity. Dry run without 'confirm'."),
]

SERVER = [
    ("/authmod warn [minutes]", 2, "Restart countdown bar for everyone. Default if no minutes given."),
    ("/authmod warn cancel", 2, "Clear the bar if the restart is called off."),
    ("/authmod lockdown on|off|status", 2, "Close the SMP to players (staff still get in). Shared between lobby and SMP."),
    ("/authmod testing on [reason] | off | status", 2, "Testing mode banner: tells players things may restart or roll back."),
    ("/authmod lag", 2, "Last minute of lag, and whether it is BLOCKING (a stall) or COMPUTE (too much work)."),
    ("/authmod ships", 2, "Count of Sable ships/sub-levels loaded."),
    ("/authmod rpm status|scan|cap", 2, "Create rotation-speed cap: check it is active, scan for over-speed machines."),
    ("/authmod forceload status [radius]", 2, "Force-loaded chunks near you (they tick 24/7)."),
    ("/authmod forceload clear <radius>", 2, "Release force-loaded chunks near you."),
    ("/authmod itemclear [now]", 2, "When the next ground-item clear is / run it now."),
    ("/authmod anchors", 2, "RTP anchor status."),
    ("/sablecollision on|off|status", 2, "Ship collisions breaking blocks, on or off until the next restart."),
    ("/authmod movegrace <player> [seconds]", 2, "Stop the anti-cheat flagging a player for a few seconds (e.g. after a launch)."),
    ("/authmod tolobby <player>", 2, "Send an online player back through the lobby."),
    ("/lobby", 2, "Lobby tools. /lobby clearplatform [radius] removes a stray emergency platform."),
    ("/lobby greeter <text>  /lobby greeter dialog", 2, "Change what the lobby greeter NPC says."),
    ("/authmod obsidianreport", 2, "Push a player report to the staff notes vault."),
]

MAIL = [
    ("/mail", 0, "Your mailbox. Click a letter to read it; Claim takes the items; 'Read as a book' opens it as a book."),
    ("/mail help", 0, "The player guide. Point players here."),
    ("/mail send <player>", 0, "Write a letter in chat, then pack up to 9 stacks, then Send. 10 a day for players."),
    ("/mail admin send <player>", 3, "Staff letter. No daily limit. Items you pack are COPIED (you keep yours)."),
    ("/mail admin sendonline", 3, "To everyone online. Press Send twice (confirm) within 30 seconds."),
    ("/mail admin sendall", 3, "To EVERY player on record. Same double-press confirm. Use for season news and rewards."),
]


def build():
    story = []
    story += [Spacer(1, 50 * mm), p("Coffee's Aero SMP", TITLE), Spacer(1, 4 * mm),
              p("Admin Handbook", ParagraphStyle("T2", parent=TITLE, fontSize=22)), Spacer(1, 10 * mm),
              p("Season 3 · for staff only · please don't share outside the team", SUB), Spacer(1, 6 * mm),
              p("Covers CoffeesAeroAuth 1.13.9, Skins 1.1.1, Guard 1.0.8. 'Lvl' is the permission level a "
                "command needs: 2 = moderator, 3 = admin, 4 = owner.", SUB),
              PageBreak()]

    story += [p("1. How we run things", H1)]
    story += bullets([
        "<b>Be kind first.</b> Most problems are confusion, not cheating. Ask, explain, then act.",
        "<b>Write it down.</b> Bans, freezes and big changes go to Discord automatically - add the <i>why</i> "
        "in the staff channel so the next admin understands.",
        "<b>Dry runs exist for a reason.</b> transferaccount, freshstart and aeroid move only show a plan "
        "until you add <font face='Courier-Bold'>confirm</font>. Read the plan.",
        "<b>Bypass is a tool, not a mode.</b> Turn <font face='Courier-Bold'>/aerobypass</font> off as soon "
        "as you are done.",
        "<b>When unsure, ask the owner.</b> Nothing here is urgent enough to guess.",
    ])
    story += [Spacer(1, 4 * mm), box("<b>How players get in:</b> everyone connects to the gate, which checks "
              "premium vs offline, then lands in the <b>lobby</b>, then is handed to <b>Survival</b>. If Survival "
              "is down, players are held in the lobby until it is back - that is normal, not a bug.")]

    story += [p("2. Moderation", H1), table(MODERATION)]
    story += [Spacer(1, 3 * mm), box("<b>Freeze vs ban.</b> Use <font face='Courier-Bold'>confiscate</font> while "
              "you investigate (the player stays online and can talk to you), and <font face='Courier-Bold'>ban"
              "</font> once you have decided.")]

    story += [PageBreak(), p("3. Accounts, names and identity", H1)]
    story += [p("Every account's identity comes from its <b>exact</b> name, capital letters included. "
                "'Steve' and 'steve' are two different players. When a command misses, it lists the "
                "near matches: pick the exact one.", BODY), Spacer(1, 3 * mm), table(ACCOUNTS)]
    story += [Spacer(1, 3 * mm), box("<b>Premium player changed their Minecraft name?</b> That creates a NEW "
              "account. Don't wipe anything - use <font face='Courier-Bold'>/authmod transferaccount &lt;old&gt; "
              "&lt;new&gt;</font>, read the plan, then add <font face='Courier-Bold'>confirm</font>.")]

    story += [p("4. Mail", H1)]
    story += [p("Mail is for letters and parcels between players, plus rewards from the server. "
                "There is no subject line any more: the first line of the letter is what shows in the inbox.", BODY),
              Spacer(1, 3 * mm), table(MAIL), Spacer(1, 3 * mm)]
    story += bullets([
        "<b>No coins by mail, for anyone.</b> Spurs and every Numismatics item only change hands in the world - "
        "in person, at a shop or a vendor. The parcel refuses them, even hidden inside a shulker box.",
        "Letters go through the chat filter. Every player parcel is logged to the staff Discord.",
        "Parcels nobody collects go back to the sender; unclaimed parcels can't be deleted.",
        "The welcome kit and vote rewards still arrive as system mail - that's the only way spurs travel.",
    ])

    story += [PageBreak(), p("5. Server health and restarts", H1), table(SERVER)]
    story += [Spacer(1, 3 * mm), p("Restart checklist", H2)]
    story += bullets([
        "<font face='Courier-Bold'>/authmod warn 10</font> so builders can finish and park ships.",
        "Restart from the panel. Players are held in the lobby and readmitted automatically when Survival answers again.",
        "After it is up: <font face='Courier-Bold'>/authmod lag</font> - a calm server reads mostly COMPUTE with a low peak.",
        "Short freezes every 5 minutes are the world autosave. They are known; don't restart for them.",
    ])

    story += [p("6. Discord", H1)]
    story += bullets([
        "<b>Live players card</b> (admin status channel): Survival and Lobby counts with names, refreshed every "
        "minute. If 'Updated' is more than a few minutes old, the lobby itself may be down.",
        "<b>Watchdog channel</b>: bans, freezes, suspicious logins, mail parcels. HIGH and CRITICAL alerts ping the admin role "
        "and carry Ban/Unban buttons.",
        "<b>Name approvals</b> arrive with Approve/Reject buttons - same as /authmod approve and reject.",
        "<b>Console channel</b> (admins only) mirrors the server console and runs commands. Treat it like the "
        "console: no experiments.",
    ])

    never = [
        "Never remove or downgrade a content mod on the live server. Every block from it vanishes from the world.",
        "Never run /authmod freshstart, transferaccount or aeroid move without reading the dry run first.",
        "Never leave /aerobypass on, and never use it to take or move another player's things.",
        "Never paste secrets, passwords, server addresses or console output with player IPs into public channels.",
        "Never give out spurs or coins outside the vote rewards and the welcome kit.",
        "Never restart Survival to fix a short freeze - check /authmod lag first and tell the owner.",
    ]
    story += [KeepTogether([p("7. Never do this", H1),
                            box("<br/>".join("<b>%d.</b> %s" % (i + 1, n) for i, n in enumerate(never)), WARN,
                                colors.HexColor("#FBEAEA"))])]
    story += [Spacer(1, 6 * mm), p("Questions? Ask the owner (MrCoffeeBench) in the staff channel. Thanks for "
                                   "looking after the server!", SMALL)]
    return story


def on_page(c, doc):
    c.saveState()
    c.setFillColor(COFFEE)
    c.rect(0, A4[1] - 8 * mm, A4[0], 8 * mm, fill=1, stroke=0)
    c.setFont("Helvetica", 8)
    c.setFillColor(INK)
    if doc.page > 1:
        c.drawString(20 * mm, 10 * mm, "Coffee's Aero SMP - Admin Handbook - staff only")
        c.drawRightString(A4[0] - 20 * mm, 10 * mm, "Page %d" % doc.page)
    c.restoreState()


def guard_no_secrets(story_text):
    if re.search(r"\b\d{1,3}(\.\d{1,3}){3}\b", story_text):
        sys.exit("ERROR: an IP address is in the handbook")
    if re.search(r"https?://|discord\.com/api|webhook/|\.gg/|lagless|duckdns|:\d{4,5}\b", story_text, re.I):
        sys.exit("ERROR: a URL, host or port is in the handbook")


def main():
    story = build()
    text = " ".join(getattr(f, "text", "") for f in story)
    for rows in (MODERATION, ACCOUNTS, SERVER, MAIL):
        text += " ".join(" ".join(map(str, r)) for r in rows)
    guard_no_secrets(text)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=18 * mm,
                            bottomMargin=18 * mm, title="Coffee's Aero SMP - Admin Handbook",
                            author="MrCoffeeBench", subject="Staff handbook, Season 3")
    doc.build(story, onFirstPage=on_page, onLaterPages=on_page)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
