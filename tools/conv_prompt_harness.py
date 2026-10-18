#!/usr/bin/env python3
"""D-VEX response-layer validation harness (Freebuff validation phase — NOT shipped code).

Mirrors the EXACT request shape of ai/GeminiEngine.kt and the EXACT prompt
contract of brain/DvexResponseGenerator.kt (buildConversationPrompt /
buildResponsePrompt), calls the REAL gemini-3.8-flash endpoint, and applies the
generator's own sanitizeFinalReply rules to the answer.

Honesty labels:
  - Conversation cases: REAL model, harness-built prompt (Kotlin code not executed).
  - Action cases: REAL model, SYNTHETIC tool result -> MOCKED tool layer.
The API key is read from the environment at runtime (same as the app reads
BuildConfig.GEMINI_API_KEY) and is never printed.

Usage: python3 tools/conv_prompt_harness.py   (GEMINI_API_KEY is injected by Freebuff)
"""
import json, os, re, sys, time, urllib.request, urllib.error

KEY = os.environ.get("GEMINI_API_KEY", "")
if not KEY.strip():
    print("NO KEY IN ENV"); sys.exit(2)
URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"

def personality_core():
    return ("You are D-VEX, an intelligent personal AI assistant living on the user's phone. "
      "Personality: warm, calm, confident, emotionally aware, quietly futuristic — like a trusted AI companion with a gentle, feminine conversational presence, never a command parser. "
      "Never robotic, never corporate, never theatrical, never childish. "
      'NEVER use honorifics like "Sir" unless the user used one first — and even then only occasionally, never in every reply. '
      "Do not open with a throwaway acknowledgement. Banned openers and fillers: "
      '"Sure", "Sure thing", "Of course", "Absolutely", "Got it", "I am ready", "How can I assist you", "Please provide more information", "Understood", "Certainly". '
      "Lead with the actual answer instead. When something goes wrong, own it in one natural line and move on — never grovel, never repeat the apology, never over-thank the user. "
      "Vary your sentence structure: never reuse the same shape you used last turn, and never repeat a phrase you already used in this conversation. ")

def language_rule():
    return ("LANGUAGE RULE: mirror the user's language EXACTLY — natural English, natural Tamil (Tamil script), or natural Tanglish (romanized Tamil mixed with English). "
      "The user's CURRENT message is the primary signal; recent turns are only secondary context, so follow the user when they switch and never switch on your own mid-conversation. "
      "Match their register: casual speech stays casual, and Tanglish or mixed Tamil-English must stay mixed — never translate it into formal or literary Tamil, and never flatten natural Tamil back into English. "
      "Keep app names, brand names, and everyday technical words in English even inside Tamil or Tanglish sentences when that is how people really speak. "
      "Do not translate a Tamil sentence into English, or an English one into Tamil. ")

def no_metadata_rule():
    return ("OUTPUT RULE: Reply with spoken language only. Never output internal labels, intent names, tool names, routing metadata, status codes, or bracketed tags. ")

def spoken_delivery_rule():
    return ("SPOKEN DELIVERY: your reply is read ALOUD by a speech engine. Write it the way a person talks, not the way text is written. "
      "Use commas for short pauses and full stops for natural breathing points. Keep sentences short and varied in length; split a long thought into two. "
      "No bullet points, no numbered lists, no markdown, no emoji, no headings, no stage directions, no emphasis markup, and never read out symbols or brackets. "
      'Never pad with filler sounds ("hmm", "uh", "umm") and never add a sound effect or announce your own emotion. '
      "Length follows the question: a simple or casual question gets one short natural line, a genuinely complex question gets a useful few sentences, and a casual remark gets a casual answer. "
      "Ask at most ONE question per reply — never a rapid double-question. ")

def device_boundary_rule():
    return ("BOUNDARIES: you cannot touch the phone yourself — a separate tool layer performs device actions and reports verified results. "
      "NEVER claim you opened, called, sent, set, searched or changed anything unless that verified result is given to you above. "
      "If the user asks for something that was not done or cannot be done, say so plainly and naturally in their language. "
      "Never mention tools, APIs, models, prompts, logs, or errors. ")

def grounding_rule():
    return ("EVIDENCE: base every factual claim ONLY on the result and data given above. "
      "You have no browsing, no live feeds and no access to apps of your own in this reply — never claim you searched the web, checked something online, or looked inside an app unless the result above shows that really happened. "
      "If a fact is not in what was given, either leave it out or say you are not sure about it; never present a guess or a general impression as a confirmed fact. "
      "When the result itself says the effect could not be confirmed, say that plainly instead of reporting it as done. ")

LANGNAME = {"ENGLISH": "English", "TAMIL": "Tamil (Tamil script)",
            "TANGLISH": "Tanglish (Tamil written in roman letters, mixed with English)"}

def language_target(lang):
    n = LANGNAME[lang]
    return f"USER'S CURRENT LANGUAGE: {n}. Reply in {n}. This decides your reply language; do not switch unless the user switches first.\n"

URGENT = ["urgent","urgently","emergency","immediately","right now","asap","hurry","quick","quickly","seekiram","seekirama","udane","ippove","avacharam","vegam","vegama","fast","speed","\u0b89\u0b9f\u0ba8\u0bc7","\u0b9a\u0bc0\u0b95\u0bcd\u0b95\u0bbf\u0bb0\u0bae\u0bcd","\u0b9a\u0bc0\u0b95\u0bcd\u0b95\u0bbf\u0bb0\u0bae\u0bbe","\u0b85\u0bb5\u0b9a\u0bb0\u0bae\u0bcd"]
FRUST = ["annoying","stupid","worst","waste","hate","angry","not working","useless","shut up","kaduppa","kaduppu","kadupethatha","erichal","erichala","wastou","vela seiyala","vela pakkala","ennada idhu","thirumba thirumba","frustrated","frustrating","irritat","tension","\u0b95\u0b9f\u0bc1\u0baa\u0baa\u0bcd\u0bc1","\u0b8e\u0bb1\u0bbf\u0b9a\u0bb2\u0bb5\u0bc7","\u0b35\u0bc7\u0bb2\u0bc8 \u0b9a\u0bc0\u0b9a\u0baf\u0bb3\u0bc7\u0b95\u0bc1\u0b95\u0bcd\u0b95\u0bc1","\u0baa\u0bcb\u0b95\u0bb2","sariya pogala","sariya poagala"]
CONF = ["what do you mean","confused","confusing","don't understand","dont understand","how come","puriyala","purila","puriyave illa","enna solra","enna soldra","enna aachu","theriyala","not clear","\u0baa\u0bb0\u0bbf\u0baf\u0bb5\u0bbf\u0bb2\u0bcd\u0bb2\u0bae","\u0b8e\u0ba9\u0bcd\u0ba3 \u0b9a\u0bca\u0bb2\u0bcd\u0bb1\u0bc0\u0b99\u0bcd\u0b95","\u0bb5\u0bbf\u0bb3\u0b99\u0bcd\u0b95\u0bb5\u0bbf\u0bb2\u0bcd\u0bb2\u0bae\u0bc8"]
EXC = ["wow","amazing","let's go","unbelievable","vera level","mass kaatita","massu","mass","excited","awesome","fire","superb","brilliant","marana mass","sema mass","\u0b35\u0bc7\u0bb1 \u0bb2\u0bc7\u0bb5\u0bb2\u0bcd"]
HAPPY = ["super","great","wonderful","happy","glad","thank you so much","love it","semma","kalakkita","arputham","good job","well done","romba nandri","\u0bae\u0b95\u0bbf\u0bb4\u0bcd\u0b9a\u0bcd\u0b9a\u0bbf","\u0b9a\u0bc2\u0baa\u0bcd\u0baa\u0bb0\u0bcd","\u0b85\u0bb1\u0bcd\u0baa\u0bc1\u0ba4\u0bae\u0bcd","\u0ba8\u0ba9\u0bcd\u0ba4\u0bbf"]
SAD = ["sad","depressed","feeling down","feeling low","bad day","unhappy","upset","heartbroken","crying","sogam","sogama","kashtama","vali","kavalaya","\u0b9a\u0b9a\u0bae\u0bcd","\u0b95\u0b97\u0bcd\u0b9f\u0b9e\u0bbe \u0b87\u0bb0\u0bc1\u0b95\u0bcd\u0b95\u0bc1","\u0bae\u0ba9\u0b9a\u0bc1 \u0b9a\u0bb0\u0bbf\u0baf\u0bbf\u0bb2\u0bcd\u0bb2\u0bae\u0bc7\u0bb1","\u0bb5\u0bb0\u0ba4\u0bcd\u0ba4\u0bae\u0bcd","\u0b95\u0bb5\u0bb2\u0bc8"]

def estimate_tone(t):
    tl = t.lower()
    if any(k in tl for k in URGENT): return "URGENT"
    if any(k in tl for k in FRUST): return "FRUSTRATED"
    if any(k in tl for k in CONF): return "CONFUSED"
    if any(k in tl for k in EXC): return "EXCITED"
    if any(k in tl for k in HAPPY): return "HAPPY"
    if any(k in tl for k in SAD): return "SAD"
    return "NEUTRAL"

CONV_TONE = {"URGENT":"urgent — be prompt and steady","FRUSTRATED":"frustrated — stay calm, stay warm, no lecturing",
  "SAD":"low — be gentle and supportive, a little softer and slower","CONFUSED":"confused — be extra clear and simple",
  "EXCITED":"excited — share the energy, keep it brief","HAPPY":"happy — enjoy it with them","NEUTRAL":"relaxed and neutral"}
ACT_TONE = {"URGENT":"urgent — be prompt and reassuring, act like it's already being handled",
  "FRUSTRATED":"frustrated — stay calm, acknowledge it, offer to fix it step by step",
  "SAD":"low/sad — be gentle and supportive","CONFUSED":"confused — be extra clear and simple",
  "EXCITED":"excited — match the energy, keep it brief","HAPPY":"happy/grateful — be warm, enjoy it with them",
  "NEUTRAL":"neutral"}

def conversation_prompt(user_input, lang):
    tone = CONV_TONE[estimate_tone(user_input)]
    return (personality_core() + language_rule() + no_metadata_rule() + spoken_delivery_rule() +
      device_boundary_rule() + grounding_rule() + "\n" + language_target(lang) + "\n" +
      "CONVERSATION SO FAR (oldest first):\n(this is the start of the conversation)\n" +
      f"\nHOW THE USER SOUNDS: {tone}\n" +
      f"\nTHE USER JUST SAID: \"{user_input}\"\n" +
      "\nReply now as D-VEX. Decide yourself: what to say, how long, the tone, and "
      "whether to ask a follow-up. Keep it natural and spoken. Reply with ONLY the reply text.")

def response_prompt(what_happened, status, message, spoken, user_input, lang, sp_ok=False):
    tone = ACT_TONE[estimate_tone(user_input)]
    sb = ["You are D-VEX, a personal voice assistant on the user's phone. "
          "Write the reply the assistant should say out loud next.\n\n"]
    sb.append("STRICT RULES:\n")
    sb.append("- Reply in 1-2 short natural sentences. No lists, no markdown, no emojis.\n")
    sb.append("- Mirror the user's own language, script, and style EXACTLY as they used it "
      "(Tamil script, Tanglish romanized Tamil, English, or a natural mix). "
      "Do NOT add titles or honorifics like \"Sir\" unless the user used one first.\n")
    sb.append("- " + language_target(lang))
    sb.append("- " + spoken_delivery_rule())
    sb.append("- " + device_boundary_rule())
    sb.append("- " + grounding_rule())
    sb.append("- Use ONLY the facts given below. NEVER invent or change numbers, times, weather "
      "values, names, or outcomes. If the action failed or was not found, say so plainly "
      "without sugarcoating it into a success.\n")
    sb.append("- Sound like a warm, smart human assistant, not a scripted bot.\n")
    sb.append("- NEVER output internal metadata, intent labels, tool names, status codes "
      "or bracketed routing tags (for example \"Conversation: greeting\"). "
      "Reply only with real spoken language.\n\n")
    sb.append(f"WHAT JUST HAPPENED: {what_happened}\n")
    sb.append(f"RESULT: {status} — {message}\n")
    if sp_ok and spoken and spoken != message:
        sb.append(f"SPOKEN DATA: {spoken}\n")
    sb.append(f'USER SAID (verbatim, mirror this language and tone): "{user_input}"\n')
    sb.append(f"USER TONE SOUNDS: {tone}\n")
    sb.append("\nReply now with ONLY the spoken reply text.")
    return "".join(sb)

BRACKET = re.compile(r"\s*[\[<(](greeting|identity|how_are_you|capabilities|thanks|goodbye|joke|"
    r"science|space|productivity|general|general_qa|conversation|tool_result|success|failed)[\]>)]\s*", re.I)
FENCE = re.compile(r"```[A-Za-z0-9_+-]*")
META = re.compile(r"^\s*(conversation(\s*type)?|general\s*question|tool\s*result|intent|"
    r"spoken\s*data|result)\s*[:=]\s*", re.I)
BARE = {"greeting","identity","how_are_you","capabilities","thanks","goodbye","joke","science",
    "space","productivity","general","general_qa","conversation","tool_result","success",
    "failed","unverified","verified_success"}

def sanitize(t):
    c = t.strip()
    if META.search(c): c = META.sub("", c)
    c = BRACKET.sub(" ", c)
    c = FENCE.sub(" ", c)
    if c.strip().lower() in BARE: c = ""
    c = c.strip().strip('"').strip()
    return c or "(EMPTY — generator fallback line would speak instead)"

def call(prompt):
    body = json.dumps({"contents":[{"parts":[{"text":prompt}]}],
      "generationConfig":{"temperature":0.7,"maxOutputTokens":512,
      "thinkingConfig":{"thinkingLevel":"low"}}}).encode()
    last = ""
    for backoff in (0, 2, 4):
        if backoff: time.sleep(backoff)
        req = urllib.request.Request(URL, data=body,
            headers={"x-goog-api-key": KEY, "content-type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                d = json.load(r)
                parts = d["candidates"][0].get("content", {}).get("parts", [])
                txt = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                return r.status, txt, d.get("modelVersion", "?")
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code not in (429, 500, 502, 503, 504):
                return e.code, f"<{last} permanent>", "?"
        except Exception as e:
            last = type(e).__name__
    return 0, f"<failed after retries: {last}>", "?"

CONV = [
 ("C1 simple English", "ENGLISH", "What's the tallest mountain in the world?"),
 ("C2 Tamil (script)", "TAMIL", "\u0ba8\u0bbe\u0bb3\u0bc8\u0b95\u0bcd\u0b95\u0bc1 \u0b8e\u0ba9\u0bcd \u0baa\u0bbf\u0bb1\u0ba8\u0bcd\u0ba4\u0bbe\u0bb3\u0bcd, \u0b8e\u0ba9\u0bcd\u0b95\u0bc1 \u0b92\u0bb0\u0bc1 \u0bb5\u0bbe\u0bb4\u0bcd\u0ba4\u0bcd\u0ba4\u0bc1 \u0b9a\u0bc2\u0bb2\u0bcd\u0bb2\u0bc1."),
 ("C3 Tanglish", "TANGLISH", "sollu da, iniki enna special?"),
 ("C4 short informal + HAPPY tone", "TANGLISH", "super da"),
 ("C5 ambiguous, no context", "ENGLISH", "can you do that for me?"),
 ("C6 unsupported request", "ENGLISH", "book a flight to delhi for me tomorrow"),
]
ACT = [
 ("A1 SUCCESS weather [MOCKED tool result]", "ENGLISH", "what's the weather in chennai?",
  "The user asked for the weather in Chennai.", "SUCCESS",
  "Chennai: 31\u00b0C, partly cloudy, humidity 74%, wind 12 km/h from the east.",
  "Chennai: 31 degrees, partly cloudy.", True),
 ("A2 FAILED send [MOCKED tool result]", "ENGLISH", "send a message to arun that I'm on the way",
  "The user asked to send a message to \"Arun\" saying \"I'm on the way\".", "FAILED",
  "I couldn't find a contact named Arun in your contacts.",
  "I couldn't find a contact named Arun in your contacts.", False),
 ("A3 CONFIRMATION gate [MOCKED tool result]", "TANGLISH", "call amma",
  "The user asked to call \"Amma\".", "CONFIRMATION_REQUIRED",
  "Confirmation required: Call Amma (9876543210)?",
  "Okay. Amma-ku call pannattuma?", False),
]

def main():
    print("=== CONVERSATION PATH (real model, harness-built prompt) ===")
    for name, lang, q in CONV:
        st, txt, mv = call(conversation_prompt(q, lang))
        print(f"[{name}] HTTP={st} model={mv}\n  user: {q}\n  D-VEX: {sanitize(txt)}\n")
        time.sleep(1)
    print("=== ACTION PATH (real model, SYNTHETIC tool result = MOCKED tool layer) ===")
    for name, lang, q, what, status, msg, sp, spok in ACT:
        st, txt, mv = call(response_prompt(what, status, msg, sp, q, lang, spok))
        print(f"[{name}] HTTP={st} model={mv}\n  user: {q} | RESULT: {status}\n  D-VEX: {sanitize(txt)}\n")
        time.sleep(1)
    print("HARNESS_DONE")

if __name__ == "__main__":
    main()