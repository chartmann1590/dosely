#!/usr/bin/env python3
"""
Uploads all assets, translations, and signed release bundle to Google Play Store via the Developer API (androidpublisher v3).
"""

import csv
import glob
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

# Ensure UTF-8 output in Windows terminal
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8")

KEY_FILE = r"C:\Users\Charles\.play\dosely-play-service-account.json"
PACKAGE_NAME = "com.charles.dosely"
AAB_PATH = r"H:\med-tracker\dosely-release.aab"
TRANSLATIONS_CSV = r"H:\med-tracker\store_assets\listing\dosely_play_listing_translations.csv"
ICON_PATH = r"H:\med-tracker\store_assets\icons\icon_512.png"
FEATURE_PATH = r"H:\med-tracker\store_assets\feature\feature_1024x500.png"
SCREENSHOTS_DIR = r"H:\med-tracker\store_assets\screenshots"
WEAR_SCREENSHOTS_DIR = r"H:\med-tracker\store_assets\wear_screenshots"

# Mapping from CSV language code to Google Play supported language codes
LANG_MAP = {
    "uk-UA": "uk",
    "id-ID": "id",
    "ar-XA": "ar",
}

# Regional duplicates to broaden reach
REGIONAL_ALIASES = {
    "en-US": ["en-GB", "en-CA", "en-AU"],
    "es-ES": ["es-419", "es-US"],
    "pt-BR": ["pt-PT"],
    "fr-FR": ["fr-CA"],
}

def main():
    print(f"=== Publishing Dosely to Google Play Store ({PACKAGE_NAME}) ===")
    
    if not os.path.exists(KEY_FILE):
        raise FileNotFoundError(f"Service account key not found at {KEY_FILE}")
    if not os.path.exists(AAB_PATH):
        raise FileNotFoundError(f"AAB not found at {AAB_PATH}")

    creds = service_account.Credentials.from_service_account_file(
        KEY_FILE,
        scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    service = build("androidpublisher", "v3", credentials=creds)

    print("Opening a new Google Play edit draft...")
    edit = service.edits().insert(packageName=PACKAGE_NAME, body={}).execute()
    edit_id = edit["id"]
    print(f"Edit ID: {edit_id}")

    try:
        # 1. Update Details
        print("\n--- 1. App Contact Details ---")
        try:
            details_res = service.edits().details().patch(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                body={
                    "contactEmail": "me@charleshartmann.com",
                    "contactWebsite": "https://charleshartmann.com",
                    "defaultLanguage": "en-US",
                }
            ).execute()
            print("Updated details:", details_res)
        except Exception as e:
            print("Warning: could not patch app details:", e)

        # 2. Upload Listings and Translations
        print("\n--- 2. Store Listings & Translations ---")
        translations = []
        with open(TRANSLATIONS_CSV, encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            for row in reader:
                translations.append(row)

        for row in translations:
            orig_lang = row["language"]
            play_lang = LANG_MAP.get(orig_lang, orig_lang)
            title = row["app_name"]
            short_desc = row["short_description"]
            full_desc = row["full_description"]

            langs_to_update = [play_lang]
            if orig_lang in REGIONAL_ALIASES:
                langs_to_update.extend(REGIONAL_ALIASES[orig_lang])

            for target_lang in langs_to_update:
                try:
                    res = service.edits().listings().update(
                        packageName=PACKAGE_NAME,
                        editId=edit_id,
                        language=target_lang,
                        body={
                            "language": target_lang,
                            "title": title,
                            "shortDescription": short_desc,
                            "fullDescription": full_desc,
                        }
                    ).execute()
                    print(f"  [OK] Listing: {target_lang} -> '{res.get('title')}'")
                except Exception as e:
                    print(f"  [FAIL] Listing for {target_lang}: {e}")

        # 3. Upload Graphics (Icon, Feature Graphic, Screenshots)
        print("\n--- 3. Graphics & Screenshots ---")
        # App Icon
        try:
            icon_res = service.edits().images().upload(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                language="en-US",
                imageType="icon",
                media_body=MediaFileUpload(ICON_PATH, mimetype="image/png")
            ).execute()
            print(f"  [OK] App Icon uploaded (ID: {icon_res.get('image', {}).get('id')})")
        except Exception as e:
            print(f"  [FAIL] App Icon upload: {e}")

        # Feature Graphic
        try:
            feat_res = service.edits().images().upload(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                language="en-US",
                imageType="featureGraphic",
                media_body=MediaFileUpload(FEATURE_PATH, mimetype="image/png")
            ).execute()
            print(f"  [OK] Feature Graphic uploaded (ID: {feat_res.get('image', {}).get('id')})")
        except Exception as e:
            print(f"  [FAIL] Feature Graphic upload: {e}")

        # Phone Screenshots
        # Delete existing screenshots for clean state
        try:
            service.edits().images().deleteall(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                language="en-US",
                imageType="phoneScreenshots"
            ).execute()
        except Exception:
            pass

        shots = sorted(glob.glob(os.path.join(SCREENSHOTS_DIR, "*.png")))
        for s in shots:
            filename = os.path.basename(s)
            try:
                shot_res = service.edits().images().upload(
                    packageName=PACKAGE_NAME,
                    editId=edit_id,
                    language="en-US",
                    imageType="phoneScreenshots",
                    media_body=MediaFileUpload(s, mimetype="image/png")
                ).execute()
                print(f"  [OK] Screenshot {filename} (ID: {shot_res.get('image', {}).get('id')})")
            except Exception as e:
                print(f"  [FAIL] Screenshot {filename}: {e}")

        # Wear OS Screenshots
        if os.path.exists(WEAR_SCREENSHOTS_DIR):
            try:
                service.edits().images().deleteall(
                    packageName=PACKAGE_NAME,
                    editId=edit_id,
                    language="en-US",
                    imageType="wearScreenshots"
                ).execute()
            except Exception:
                pass

            wear_shots = sorted(glob.glob(os.path.join(WEAR_SCREENSHOTS_DIR, "*.png")))
            for ws in wear_shots:
                filename = os.path.basename(ws)
                try:
                    wshot_res = service.edits().images().upload(
                        packageName=PACKAGE_NAME,
                        editId=edit_id,
                        language="en-US",
                        imageType="wearScreenshots",
                        media_body=MediaFileUpload(ws, mimetype="image/png")
                    ).execute()
                    print(f"  [OK] Wear Screenshot {filename} (ID: {wshot_res.get('image', {}).get('id')})")
                except Exception as e:
                    print(f"  [FAIL] Wear Screenshot {filename}: {e}")

        # 4. Upload Signed Release Bundle
        print("\n--- 4. Release Bundle (AAB) Upload ---")
        media = MediaFileUpload(AAB_PATH, mimetype="application/octet-stream", resumable=True)
        req = service.edits().bundles().upload(packageName=PACKAGE_NAME, editId=edit_id, media_body=media)
        bundle_res = None
        last_pct = -1
        while bundle_res is None:
            status, bundle_res = req.next_chunk()
            if status:
                pct = int(status.progress() * 100)
                if pct != last_pct and pct % 10 == 0:
                    print(f"  Upload progress: {pct}%")
                    last_pct = pct
        version_code = bundle_res["versionCode"]
        print(f"  [OK] Bundle uploaded: Version Code {version_code}, SHA256: {bundle_res.get('sha256')}")

        # 5. Assign Bundle to Tracks
        print("\n--- 5. Assign to Release Track ---")
        track_body = {
            "track": "internal",
            "releases": [{
                "name": f"1.1.0 ({version_code})",
                "versionCodes": [str(version_code)],
                "status": "completed",
                "releaseNotes": [{
                    "language": "en-US",
                    "text": "GLP-1 companion update: Pharmacokinetic decay curves, 6-site injection rotation, daily hydration & protein care, Wear OS companion, and Dosely+ ad-free subscription."
                }]
            }]
        }
        track_res = service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track="internal",
            body=track_body
        ).execute()
        print("  [OK] Internal test track updated:", track_res.get("track"))

        # 6. Commit Edit
        print("\n--- 6. Committing Edit to Play Store ---")
        commit_res = service.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print("\n=======================================================")
        print("🎉 SUCCESS! Edit committed to Google Play Console!")
        print("Commit details:", commit_res)
        print("=======================================================")

    except Exception as e:
        print("\n[ERROR] An error occurred during publishing:", e)
        print("Attempting to clean up draft edit...")
        try:
            service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print("Draft edit cleaned up.")
        except Exception as ce:
            print("Could not delete edit:", ce)
        raise

if __name__ == "__main__":
    main()
