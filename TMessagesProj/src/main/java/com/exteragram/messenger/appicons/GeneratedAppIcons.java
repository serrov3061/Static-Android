package com.exteragram.messenger.appicons;

import org.telegram.messenger.R;

/**
 * Icons of exteraGram's launcher icon picker. "default" is Telegram's DefaultIcon alias (the StaticGram application
 * icon), shared with LauncherIconController; "light"/"monet" are StaticGram mark variants; the rest are
 * exteraGram 13.1.1's set (their artwork, aliases org.telegram.messenger.*Icon). Where exteraGram's adaptive icons
 * use the exteraGram logo layers (ic_launcher_foreground / ic_monochrome) the StaticGram mark is used instead.
 */
public abstract class GeneratedAppIcons {

    public static AppIcon[] create() {
        return new AppIcon[]{
            AppIcon.of("default", "org.telegram.messenger.DefaultIcon", "always", R.string.AppIconDefault, null, R.string.AppIconDefaultInfo, null, 0, null, null,
                R.drawable.icon_background_sa, false, R.mipmap.icon_foreground_sa, R.drawable.ic_monochrome_static),
            AppIcon.of("light", "org.telegram.messenger.StaticLightIcon", "always", R.string.AppIconLight, null, 0, null, 0, null, null,
                R.color.ic_background_static_light, true, R.drawable.ic_foreground_static_mark, R.drawable.ic_monochrome_static),
            AppIcon.of("monet", "org.telegram.messenger.StaticMonetIcon", "monet", R.string.AppIconMonet, null, R.string.AppIconMonetInfo, null, 0, null, null,
                R.color.ic_background_monet, true, R.drawable.ic_foreground_static_monet, R.drawable.ic_monochrome_static),
            AppIcon.of("winter", "org.telegram.messenger.WinterIcon", "winter", R.string.AppIconWinter, null, R.string.AppIconWinterInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_winter_background, false, R.drawable.ic_monochrome_static, R.drawable.ic_monochrome_static),
            AppIcon.of("orbit", "org.telegram.messenger.OrbitIcon", "always", R.string.AppIconOrbit, null, R.string.AppIconOrbitInfo, null, 0, "exteraGram", null, R.color.ic_background, true, R.mipmap.ic_launcher_orbit_foreground, R.drawable.ic_monochrome_orbit),
            AppIcon.of("editor", "org.telegram.messenger.EditorIcon", "always", R.string.AppIconEditor, null, R.string.AppIconEditorInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_editor_background, false, R.mipmap.ic_launcher_editor_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("mole", "org.telegram.messenger.MoleIcon", "always", 0, "Mole", 0, null, 0, "whatwherewhywhen", null, R.mipmap.ic_launcher_mole_background, false, R.mipmap.ic_launcher_mole_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("echpochmak", "org.telegram.messenger.EchpochmakIcon", "always", 0, "Echpochmak", 0, null, 0, "whatwherewhywhen", null, R.mipmap.ic_launcher_echpochmak_background, false, R.mipmap.ic_launcher_echpochmak_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("avatar", "org.telegram.messenger.AvatarIcon", "always", 0, "Avatar", 0, null, 0, "cIick_cIack", null, R.mipmap.ic_launcher_avatar_background, false, R.mipmap.ic_launcher_avatar_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("candy", "org.telegram.messenger.CandyIcon", "always", 0, "Candy", 0, null, 0, "impulz3", null, R.mipmap.ic_launcher_candy_background, false, R.mipmap.ic_launcher_candy_foreground, R.drawable.ic_monochrome_candy),
            AppIcon.of("guitar", "org.telegram.messenger.GuitarIcon", "always", 0, "Guitar", 0, null, 0, "nekto_nkt", null, R.mipmap.ic_launcher_guitar_background, false, R.mipmap.ic_launcher_guitar_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("sus", "org.telegram.messenger.SusIcon", "always", R.string.AppIconSus, null, 0, null, 0, "MilesBlooper", null, R.color.ic_background_sus, true, R.mipmap.ic_launcher_sus_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("frog", "org.telegram.messenger.FrogIcon", "always", 0, "Frog", 0, null, 0, "cIick_cIack", null, R.mipmap.ic_launcher_frog_background, false, R.mipmap.ic_launcher_frog_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("hamster", "org.telegram.messenger.HamsterIcon", "always", 0, "Hamster", 0, null, 0, "whatwherewhywhen", null, R.mipmap.ic_launcher_hamster_background, false, R.mipmap.ic_launcher_hamster_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("sponge", "org.telegram.messenger.SpongeIcon", "always", 0, "Sponge", 0, null, 0, "nneeuurr", null, R.mipmap.ic_launcher_sponge_background, false, R.mipmap.ic_launcher_sponge_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("invincible", "org.telegram.messenger.InvincibleIcon", "always", R.string.AppIconInvincible, null, R.string.AppIconInvincibleInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_invincible_background, false, R.mipmap.ic_launcher_invincible_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("flappy", "org.telegram.messenger.FlappyIcon", "always", 0, "Flappy", 0, null, 0, "nneeuurr", null, R.mipmap.ic_launcher_flappy_background, false, R.mipmap.ic_launcher_flappy_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("nyan_cat", "org.telegram.messenger.NyanCatIcon", "always", 0, "Nyan Cat", 0, null, 0, "n1ksly", null, R.mipmap.ic_launcher_nyan_cat_background, false, R.mipmap.ic_launcher_nyan_cat_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("ice_age", "org.telegram.messenger.IceAgeIcon", "always", R.string.AppIconIceAge, null, R.string.AppIconIceAgeInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_ice_age_background, false, R.mipmap.ic_launcher_ice_age_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("fish", "org.telegram.messenger.FishIcon", "always", 0, "Fish", 0, null, 0, "whatwherewhywhen", null, R.mipmap.ic_launcher_fish_background, false, R.mipmap.ic_launcher_fish_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("psychosis", "org.telegram.messenger.PsychosisIcon", "always", 0, "Psychosis", 0, null, 0, "cIick_cIack", null, R.mipmap.ic_launcher_psychosis_background, false, R.mipmap.ic_launcher_psychosis_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("xp", "org.telegram.messenger.XpIcon", "always", 0, "XP", 0, null, 0, "weizoqq", null, R.mipmap.ic_launcher_xp_background, false, R.mipmap.ic_launcher_xp_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("nasa", "org.telegram.messenger.NasaIcon", "always", 0, "NASA", 0, null, 0, "cIick_cIack", null, R.mipmap.ic_launcher_nasa_background, false, R.mipmap.ic_launcher_nasa_foreground, R.drawable.ic_monochrome_nasa),
            AppIcon.of("cyberpunk", "org.telegram.messenger.CyberpunkIcon", "always", R.string.AppIconCyberpunk, null, R.string.AppIconCyberpunkInfo, null, 0, "exteraGram", null, R.color.ic_background_cyberpunk, true, R.mipmap.ic_launcher_cyberpunk_foreground, R.drawable.ic_monochrome_cyberpunk),
            AppIcon.of("space", "org.telegram.messenger.SpaceIcon", "always", R.string.AppIconSpace, null, R.string.AppIconSpaceInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_space_background, false, R.mipmap.ic_launcher_space_foreground, R.drawable.ic_monochrome_space),
            AppIcon.of("jellyfish", "org.telegram.messenger.JellyfishIcon", "always", 0, "Jellyfish", 0, null, 0, "whatwherewhywhen", null, R.mipmap.ic_launcher_jellyfish_background, false, R.mipmap.ic_launcher_jellyfish_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("human", "org.telegram.messenger.HumanIcon", "always", 0, "Human", 0, null, 0, "cIick_cIack", null, R.mipmap.ic_launcher_human_background, false, R.mipmap.ic_launcher_human_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("sunset", "org.telegram.messenger.SunsetIcon", "always", R.string.AppIconSunset, null, R.string.AppIconSunsetInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_sunset_background, false, R.mipmap.ic_launcher_sunset_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("amethyst", "org.telegram.messenger.AmethystIcon", "always", R.string.AppIconAmethyst, null, R.string.AppIconAmethystInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_amethyst_background, false, R.mipmap.ic_launcher_amethyst_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("aurora", "org.telegram.messenger.AuroraIcon", "always", R.string.AppIconAurora, null, R.string.AppIconAuroraInfo, null, 0, "exteraGram", null, R.mipmap.ic_launcher_aurora_background, false, R.mipmap.ic_launcher_aurora_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("osu", "org.telegram.messenger.OsuIcon", "always", 0, "osu!", 0, null, 0, "ataeshi", null, R.mipmap.ic_launcher_osu_background, false, R.mipmap.ic_launcher_osu_foreground, R.drawable.ic_monochrome_static),
            AppIcon.of("google", "org.telegram.messenger.GoogleIcon", "always", R.string.AppIconGoogle, null, R.string.AppIconGoogleInfo, null, 0, "exteraGram", null, R.color.white, true, R.mipmap.ic_launcher_google_foreground, R.drawable.ic_monochrome_google),
            AppIcon.of("google26", "org.telegram.messenger.Google26Icon", "always", 0, "Google \u201926", 0, null, 0, "Slucket", null, R.color.white, true, R.mipmap.ic_launcher_google26_foreground, R.drawable.ic_monochrome_static),
        };
    }
}
