#!/usr/bin/env python3
"""
Uploads all assets, translations, and signed release bundle to Google Play Store via the Developer API (androidpublisher v3).
Supports staging releases to production (draft) or internal testing tracks for both Phone and Wear OS.
"""

import argparse
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
WEAR_AAB_PATH = r"H:\med-tracker\dosely-wear-release.aab"
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

PHONE_RELEASE_NOTE = (
    "GLP-1 companion update: Pharmacokinetic decay curves, 6-site injection rotation, "
    "daily hydration & protein care, Wear OS companion, and Dosely+ ad-free subscription."
)

WEAR_RELEASE_NOTE = (
    "Initial Wear OS companion release - on-wrist shot, hydration, and weight logging."
)

def parse_args():
    parser = argparse.ArgumentParser(description="Publish Dosely to Google Play Store")
    parser.add_argument("--track", default="production", choices=["production", "internal", "beta", "alpha"],
                        help="Target release track for Phone app (default: production)")
    parser.add_argument("--status", default="draft", choices=["draft", "completed", "halted", "inProgress"],
                        help="Release status (default: draft)")
    parser.add_argument("--wear-track", default="wear:production", choices=["wear:production", "wear:internal", "wear:beta"],
                        help="Target release track for Wear OS app (default: wear:production)")
    parser.add_argument("--wear-status", default="draft", choices=["draft", "completed", "halted", "inProgress"],
                        help="Wear OS release status (default: draft)")
    parser.add_argument("--update-images", action="store_true",
                        help="Force re-upload of icon, feature graphic, and screenshots")
    parser.add_argument("--validate-only", action="store_true",
                        help="Validate the edit without committing")
    return parser.parse_args()

def main():
    args = parse_args()

    print(f"=== Google Play Publishing Tool ({PACKAGE_NAME}) ===")
    print(f"Target Track (Phone): {args.track} (status: {args.status})")
    print(f"Target Track (Wear):  {args.wear_track} (status: {args.wear_status})")
    print(f"Validate only:        {args.validate_only}")

    if not os.path.exists(KEY_FILE):
        raise FileNotFoundError(f"Service account key not found at {KEY_FILE}")
    if not os.path.exists(TRANSLATIONS_CSV):
        raise FileNotFoundError(f"Translations CSV not found at {TRANSLATIONS_CSV}")

    creds = service_account.Credentials.from_service_account_file(
        KEY_FILE,
        scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    service = build("androidpublisher", "v3", credentials=creds)

    print("\nOpening a new Google Play edit draft...")
    edit = service.edits().insert(packageName=PACKAGE_NAME, body={}).execute()
    edit_id = edit["id"]
    print(f"Edit ID: {edit_id}")

    try:
        # 1. Update Contact Details
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
            print("  [OK] Updated contact details:", details_res.get("contactEmail"))
        except Exception as e:
            print("  [WARN] Could not patch app details:", e)

        # 2. Upload Listings and Translations
        print("\n--- 2. Store Listings & Translations (17 languages + regional aliases) ---")
        translations = []
        with open(TRANSLATIONS_CSV, encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            for row in reader:
                translations.append(row)

        listing_count = 0
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
                    listing_count += 1
                except Exception as e:
                    print(f"  [FAIL] Listing for {target_lang}: {e}")

        print(f"  Total listings updated: {listing_count}")

        # 3. Upload Graphics (Icon, Feature Graphic, Screenshots)
        print("\n--- 3. Graphics & Screenshots ---")
        existing_phone_imgs = service.edits().images().list(
            packageName=PACKAGE_NAME, editId=edit_id, language="en-US", imageType="phoneScreenshots"
        ).execute().get("images", [])
        existing_wear_imgs = service.edits().images().list(
            packageName=PACKAGE_NAME, editId=edit_id, language="en-US", imageType="wearScreenshots"
        ).execute().get("images", [])

        if args.update_images or len(existing_phone_imgs) == 0:
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
                    print(f"  [OK] Phone Screenshot {filename} (ID: {shot_res.get('image', {}).get('id')})")
                except Exception as e:
                    print(f"  [FAIL] Phone Screenshot {filename}: {e}")

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
        else:
            print(f"  [INFO] Phone screenshots ({len(existing_phone_imgs)}) and Wear screenshots ({len(existing_wear_imgs)}) already present. Use --update-images to replace.")

        # 4. Check or Upload Bundles
        print("\n--- 4. Release Bundles (AAB) ---")
        existing_bundles = service.edits().bundles().list(packageName=PACKAGE_NAME, editId=edit_id).execute().get("bundles", [])
        existing_version_codes = {str(b.get("versionCode")) for b in existing_bundles}
        print(f"  Existing bundles in Play Console: {existing_version_codes}")

        phone_version_code = "2"
        if phone_version_code not in existing_version_codes:
            if os.path.exists(AAB_PATH):
                print(f"  Uploading Phone AAB: {AAB_PATH}")
                media = MediaFileUpload(AAB_PATH, mimetype="application/octet-stream", resumable=True)
                req = service.edits().bundles().upload(packageName=PACKAGE_NAME, editId=edit_id, media_body=media)
                bundle_res = None
                while bundle_res is None:
                    status, bundle_res = req.next_chunk()
                phone_version_code = str(bundle_res["versionCode"])
                print(f"  [OK] Phone Bundle uploaded: Version Code {phone_version_code}")
            else:
                print(f"  [WARN] Phone bundle {AAB_PATH} not found.")
        else:
            print(f"  [OK] Phone bundle versionCode {phone_version_code} ready in Console.")

        wear_version_code = "3"
        if wear_version_code not in existing_version_codes:
            if os.path.exists(WEAR_AAB_PATH):
                print(f"  Uploading Wear AAB: {WEAR_AAB_PATH}")
                media = MediaFileUpload(WEAR_AAB_PATH, mimetype="application/octet-stream", resumable=True)
                req = service.edits().bundles().upload(packageName=PACKAGE_NAME, editId=edit_id, media_body=media)
                bundle_res = None
                while bundle_res is None:
                    status, bundle_res = req.next_chunk()
                wear_version_code = str(bundle_res["versionCode"])
                print(f"  [OK] Wear Bundle uploaded: Version Code {wear_version_code}")
            else:
                print(f"  [WARN] Wear bundle {WEAR_AAB_PATH} not found.")
        else:
            print(f"  [OK] Wear bundle versionCode {wear_version_code} ready in Console.")

        # 5. Assign Releases to Tracks
        print("\n--- 5. Assign to Release Tracks ---")

        # Phone release
        prod_release = {
            "name": f"1.1.0 ({phone_version_code})",
            "versionCodes": [phone_version_code],
            "status": args.status,
            "releaseNotes": [{
                "language": "en-US",
                "text": PHONE_RELEASE_NOTE,
            }]
        }
        res_prod = service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=args.track,
            body={"track": args.track, "releases": [prod_release]}
        ).execute()
        print(f"  [OK] Track '{args.track}' updated (status: {args.status}, versionCode: {phone_version_code})")

        # Wear OS release
        wear_release = {
            "name": f"1.1.0 ({wear_version_code})",
            "versionCodes": [wear_version_code],
            "status": args.wear_status,
            "releaseNotes": [{
                "language": "en-US",
                "text": WEAR_RELEASE_NOTE,
            }]
        }
        res_wear = service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=args.wear_track,
            body={"track": args.wear_track, "releases": [wear_release]}
        ).execute()
        print(f"  [OK] Track '{args.wear_track}' updated (status: {args.wear_status}, versionCode: {wear_version_code})")

        # 6. Validate Edit
        print("\n--- 6. Validating Edit ---")
        val = service.edits().validate(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print(f"  [OK] Edit validation passed (ID: {val.get('id')})")

        # 7. Commit Edit
        if args.validate_only:
            print("\n--- 7. Validate-only mode requested: discarding edit ---")
            service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print("  Draft edit discarded safely.")
        else:
            print("\n--- 7. Committing Edit to Play Store ---")
            commit_res = service.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
            print("\n=======================================================")
            print("🎉 SUCCESS! Edit committed to Google Play Console!")
            print(f"Target Track (Phone): {args.track} (status: {args.status})")
            print(f"Target Track (Wear):  {args.wear_track} (status: {args.wear_status})")
            print(f"All 17 Translations & Regional Aliases Live in Console Edit.")
            print(f"Commit details: {commit_res}")
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
