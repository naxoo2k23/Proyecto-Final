package com.playmatch.servicio;

import com.playmatch.modelo.Juego;
import com.playmatch.modelo.PerfilJugador;
import com.playmatch.util.ConfiguracionDB;
import com.playmatch.util.JsonUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Motor híbrido de recomendación de jugadores para PlayMatch.
 * Combina Google Gemini API (v1beta Interactions REST) con un motor heurístico local
 * basado en juegos favoritos, géneros, estilo de juego, preferencias y valoración por estrellas.
 * Regla de visibilidad: perfiles con < 2.0 estrellas reciben penalización severa y aparecen al final.
 */
public class ServicioRecomendacionIA {

    private static final String GEMINI_INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String API_REVISION = "2026-05-20";

    private static final class ResultadoCandidato {
        final PerfilJugador perfil;
        int matchScore;
        String visibilityTier;
        String reason;
        List<String> sharedTags;

        ResultadoCandidato(PerfilJugador perfil, int matchScore, String visibilityTier, String reason, List<String> sharedTags) {
            this.perfil = perfil;
            this.matchScore = matchScore;
            this.visibilityTier = visibilityTier;
            this.reason = reason;
            this.sharedTags = sharedTags;
        }
    }

    private ServicioRecomendacionIA() {}

    public static String recomendarParaUsuarioJson(PerfilJugador actual,
                                                   List<PerfilJugador> todosLosPerfiles,
                                                   List<Juego> catalogoJuegos,
                                                   ConfiguracionDB config) {
        if (actual == null) {
            return "{\"error\":\"Perfil no encontrado\"}";
        }

        Map<String, Juego> mapaJuegos = new LinkedHashMap<>();
        for (Juego j : catalogoJuegos) {
            if (j.getCodigo() != null) {
                mapaJuegos.put(j.getCodigo().toLowerCase(Locale.ROOT), j);
            }
        }

        List<ResultadoCandidato> base = calcularRankingLocal(actual, todosLosPerfiles, mapaJuegos);
        List<String> juegosSugeridos = sugerirJuegosPorGustos(actual, catalogoJuegos, mapaJuegos);

        String engineMode = "LOCAL_MOTOR";
        String engineLabel = "Motor de Afinidad PlayMatch";
        String summary = construirResumenLocal(actual, base, juegosSugeridos);
        List<ResultadoCandidato> finales = base;

        if (config != null && config.isGeminiConfigured() && !base.isEmpty()) {
            try {
                ResultadoGemini geminiRes = consultarGemini(actual, base, juegosSugeridos, config);
                if (geminiRes != null && !geminiRes.resultados.isEmpty()) {
                    finales = fusionarYValidarReglas(actual, base, geminiRes.resultados);
                    if (geminiRes.summary != null && !geminiRes.summary.trim().isEmpty()) {
                        summary = geminiRes.summary.trim();
                    }
                    if (geminiRes.recommendedGames != null && !geminiRes.recommendedGames.isEmpty()) {
                        juegosSugeridos = geminiRes.recommendedGames;
                    }
                    engineMode = "GEMINI_API";
                    engineLabel = "Gemini AI (" + config.getGeminiModel() + ")";
                }
            } catch (Exception e) {
                System.err.println("[IA] Aviso: Gemini API no disponible (" + e.getMessage() + "). Usando respaldo heurístico local.");
                engineMode = "LOCAL_FALLBACK";
                engineLabel = "Motor PlayMatch (Respaldo Gemini)";
            }
        }

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"username\":\"").append(JsonUtils.escape(actual.getUsername())).append("\",");
        sb.append("\"engineMode\":\"").append(JsonUtils.escape(engineMode)).append("\",");
        sb.append("\"engineLabel\":\"").append(JsonUtils.escape(engineLabel)).append("\",");
        sb.append("\"geminiConfigured\":").append(config != null && config.isGeminiConfigured()).append(",");
        sb.append("\"model\":\"").append(JsonUtils.escape(config != null ? config.getGeminiModel() : "gemini-3.8-flash")).append("\",");
        sb.append("\"currentUserRating\":").append(String.format(Locale.US, "%.1f", actual.getStarRating())).append(",");
        sb.append("\"currentUserRatingCount\":").append(actual.getRatingCount()).append(",");
        sb.append("\"currentUserLowVisibility\":").append(actual.getStarRating() < 2.0).append(",");
        sb.append("\"summary\":\"").append(JsonUtils.escape(summary)).append("\",");

        sb.append("\"recommendedGames\":[");
        for (int i = 0; i < juegosSugeridos.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(JsonUtils.escape(juegosSugeridos.get(i))).append("\"");
        }
        sb.append("],");

        sb.append("\"recommendations\":[");
        for (int i = 0; i < finales.size(); i++) {
            if (i > 0) sb.append(",");
            ResultadoCandidato r = finales.get(i);
            PerfilJugador p = r.perfil;
            sb.append("{")
              .append("\"username\":\"").append(JsonUtils.escape(p.getUsername())).append("\",")
              .append("\"avatarUrl\":\"").append(JsonUtils.escape(p.getAvatarUrl())).append("\",")
              .append("\"bio\":\"").append(JsonUtils.escape(nullSafe(p.getBiografia()))).append("\",")
              .append("\"game\":\"").append(JsonUtils.escape(p.getJuego())).append("\",")
              .append("\"gameName\":\"").append(JsonUtils.escape(p.getNombreJuego())).append("\",")
              .append("\"rank\":\"").append(JsonUtils.escape(p.getRango())).append("\",")
              .append("\"role\":\"").append(JsonUtils.escape(p.getRol())).append("\",")
              .append("\"playstyle\":\"").append(JsonUtils.escape(p.getEstiloJuego())).append("\",")
              .append("\"region\":\"").append(JsonUtils.escape(p.getRegion())).append("\",")
              .append("\"preferredSchedule\":\"").append(JsonUtils.escape(p.getHorarioPreferido())).append("\",")
              .append("\"toxicLevel\":\"").append(JsonUtils.escape(p.getNivelToxicidad())).append("\",")
              .append("\"starRating\":").append(String.format(Locale.US, "%.1f", p.getStarRating())).append(",")
              .append("\"ratingCount\":").append(p.getRatingCount()).append(",")
              .append("\"cardFrame\":\"").append(JsonUtils.escape(p.getCardFrame())).append("\",")
              .append("\"equippedTitle\":\"").append(JsonUtils.escape(p.getEquippedTitle())).append("\",")
              .append("\"lowRatedPenalty\":").append(p.getStarRating() < 2.0).append(",")
              .append("\"discordTag\":\"").append(JsonUtils.escape(p.getDiscordTag())).append("\",")
              .append("\"discordConnected\":").append(p.isDiscordConnected()).append(",")
              .append("\"favoriteGames\":\"").append(JsonUtils.escape(nullSafe(p.getFavoriteGames()))).append("\",")
              .append("\"favoriteGenres\":\"").append(JsonUtils.escape(nullSafe(p.getFavoriteGenres()))).append("\",")
              .append("\"playPreferences\":\"").append(JsonUtils.escape(nullSafe(p.getPlayPreferences()))).append("\",")
              .append("\"matchScore\":").append(r.matchScore).append(",")
              .append("\"visibilityTier\":\"").append(JsonUtils.escape(r.visibilityTier)).append("\",")
              .append("\"reason\":\"").append(JsonUtils.escape(r.reason)).append("\",")
              .append("\"sharedTags\":[");
            for (int t = 0; t < r.sharedTags.size(); t++) {
                if (t > 0) sb.append(",");
                sb.append("\"").append(JsonUtils.escape(r.sharedTags.get(t))).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static List<ResultadoCandidato> calcularRankingLocal(PerfilJugador actual,
                                                                 List<PerfilJugador> todos,
                                                                 Map<String, Juego> mapaJuegos) {
        Set<String> misJuegos = extraerSetJuegos(actual);
        Set<String> misGeneros = extraerSetGeneros(actual, misJuegos, mapaJuegos);
        Set<String> misPrefs = splitTokensCsv(actual.getPlayPreferences());
        Set<String> misBioTokens = splitBioTokens(actual.getBiografia());

        List<ResultadoCandidato> resultados = new ArrayList<>();
        for (PerfilJugador cand : todos) {
            if (cand.getUsername() == null || cand.getUsername().equalsIgnoreCase(actual.getUsername())) {
                continue;
            }

            Set<String> susJuegos = extraerSetJuegos(cand);
            Set<String> susGeneros = extraerSetGeneros(cand, susJuegos, mapaJuegos);
            Set<String> susPrefs = splitTokensCsv(cand.getPlayPreferences());
            Set<String> susBioTokens = splitBioTokens(cand.getBiografia());

            int puntajeGustos = 24; // Base mínima de compatibilidad comunitaria
            List<String> sharedTags = new ArrayList<>();
            List<String> motivos = new ArrayList<>();

            // 1. Juegos en común (principal y favoritos)
            if (actual.getJuego() != null && actual.getJuego().equalsIgnoreCase(cand.getJuego())) {
                puntajeGustos += 22;
                String gName = resolverNombreJuego(cand.getJuego(), cand.getNombreJuego(), mapaJuegos);
                sharedTags.add(gName);
                motivos.add("juegan " + gName);
            }
            int extraJuegos = 0;
            for (String code : misJuegos) {
                if (susJuegos.contains(code)) {
                    String gName = resolverNombreJuego(code, code, mapaJuegos);
                    if (!sharedTags.contains(gName)) {
                        sharedTags.add(gName);
                        extraJuegos++;
                    }
                }
            }
            if (extraJuegos > 0) {
                puntajeGustos += Math.min(22, extraJuegos * 11);
                if (motivos.isEmpty()) {
                    motivos.add("comparten juegos (" + String.join(", ", sharedTags) + ")");
                }
            }

            // 2. Géneros favoritos
            int generosComunes = 0;
            for (String gen : misGeneros) {
                if (susGeneros.contains(gen)) {
                    generosComunes++;
                    if (sharedTags.size() < 6) sharedTags.add(gen);
                }
            }
            puntajeGustos += Math.min(16, generosComunes * 8);

            // 3. Preferencias sociales y estilo de juego
            int prefsComunes = 0;
            for (String pr : misPrefs) {
                if (susPrefs.contains(pr)) {
                    prefsComunes++;
                    if (sharedTags.size() < 6) sharedTags.add(pr);
                }
            }
            if (prefsComunes > 0) {
                puntajeGustos += Math.min(16, prefsComunes * 6);
                motivos.add("preferencias en común (" + prefsComunes + ")");
            }
            if (actual.getEstiloJuego() != null && actual.getEstiloJuego().equalsIgnoreCase(cand.getEstiloJuego())) {
                puntajeGustos += 6;
            }

            // 4. Complementariedad de roles y horario
            if (rolesComplementarios(actual.getRol(), cand.getRol())) {
                puntajeGustos += 6;
                motivos.add("roles complementarios");
            }
            if (mismoBloqueHorario(actual.getHorarioPreferido(), cand.getHorarioPreferido())) {
                puntajeGustos += 6;
                motivos.add("horario compatible");
            }
            int bioOverlap = 0;
            for (String t : misBioTokens) {
                if (susBioTokens.contains(t)) bioOverlap++;
            }
            puntajeGustos += Math.min(6, bioOverlap * 2);

            // 5. Regla de valoración por estrellas (< 2.0 estrellas = mucha menos recomendación)
            double stars = cand.getStarRating();
            int scoreFinal;
            String tier;
            String reason;

            String resumenMotivo = motivos.isEmpty()
                    ? "perfil compatible en comunidad"
                    : String.join(" · ", motivos);

            if (stars < 2.0) {
                scoreFinal = Math.max(8, Math.min(34, (int) Math.round(puntajeGustos * 0.28)));
                tier = "REDUCIDA";
                reason = String.format(Locale.US,
                        "Baja valoración (%.1f★) · %s",
                        stars, resumenMotivo);
            } else if (stars >= 4.5) {
                scoreFinal = Math.min(99, (int) Math.round(puntajeGustos * 1.10) + 5);
                tier = "DESTACADA";
                reason = Character.toUpperCase(resumenMotivo.charAt(0)) + resumenMotivo.substring(1);
            } else if (stars >= 3.5) {
                scoreFinal = Math.min(95, (int) Math.round(puntajeGustos * 1.02) + 2);
                tier = "ALTA";
                reason = Character.toUpperCase(resumenMotivo.charAt(0)) + resumenMotivo.substring(1);
            } else {
                scoreFinal = Math.min(82, (int) Math.round(puntajeGustos * 0.88));
                tier = "NORMAL";
                reason = Character.toUpperCase(resumenMotivo.charAt(0)) + resumenMotivo.substring(1);
            }

            resultados.add(new ResultadoCandidato(cand, scoreFinal, tier, reason, sharedTags));
        }

        ordenarResultados(resultados);
        return resultados;
    }

    private static void ordenarResultados(List<ResultadoCandidato> lista) {
        lista.sort((a, b) -> {
            boolean aLow = a.perfil.getStarRating() < 2.0;
            boolean bLow = b.perfil.getStarRating() < 2.0;
            if (aLow != bLow) return aLow ? 1 : -1;
            int cmpScore = Integer.compare(b.matchScore, a.matchScore);
            if (cmpScore != 0) return cmpScore;
            int cmpStars = Double.compare(b.perfil.getStarRating(), a.perfil.getStarRating());
            if (cmpStars != 0) return cmpStars;
            return a.perfil.getUsername().compareToIgnoreCase(b.perfil.getUsername());
        });
    }

    private static List<ResultadoCandidato> fusionarYValidarReglas(PerfilJugador actual,
                                                                    List<ResultadoCandidato> locales,
                                                                    List<ResultadoCandidato> geminiList) {
        Map<String, ResultadoCandidato> porUserLocal = new LinkedHashMap<>();
        for (ResultadoCandidato r : locales) {
            porUserLocal.put(r.perfil.getUsername().toLowerCase(Locale.ROOT), r);
        }

        Map<String, ResultadoCandidato> porUserFinal = new LinkedHashMap<>(porUserLocal);
        for (ResultadoCandidato gr : geminiList) {
            String key = gr.perfil.getUsername().toLowerCase(Locale.ROOT);
            ResultadoCandidato local = porUserLocal.get(key);
            if (local == null) continue;

            double stars = local.perfil.getStarRating();
            int score = Math.max(5, Math.min(99, gr.matchScore));
            String tier = gr.visibilityTier != null ? gr.visibilityTier.toUpperCase(Locale.ROOT) : local.visibilityTier;

            // Invariante estricta: nadie con < 2.0 estrellas puede figurar con prioridad alta ni > 34%
            if (stars < 2.0) {
                score = Math.min(34, Math.max(8, score));
                tier = "REDUCIDA";
            } else if (!"DESTACADA".equals(tier) && !"ALTA".equals(tier) && !"NORMAL".equals(tier)) {
                tier = local.visibilityTier;
            }

            List<String> tags = (gr.sharedTags != null && !gr.sharedTags.isEmpty()) ? gr.sharedTags : local.sharedTags;
            String reason = (gr.reason != null && !gr.reason.trim().isEmpty()) ? gr.reason.trim() : local.reason;
            porUserFinal.put(key, new ResultadoCandidato(local.perfil, score, tier, reason, tags));
        }

        List<ResultadoCandidato> merged = new ArrayList<>(porUserFinal.values());
        ordenarResultados(merged);
        return merged;
    }

    private static final class ResultadoGemini {
        String summary;
        List<String> recommendedGames;
        List<ResultadoCandidato> resultados = new ArrayList<>();
    }

    private static ResultadoGemini consultarGemini(PerfilJugador actual,
                                                   List<ResultadoCandidato> candidatos,
                                                   List<String> juegosBase,
                                                   ConfiguracionDB config) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("PERFIL DEL JUGADOR QUE BUSCA RECOMENDACIONES:\n")
              .append("- Usuario: ").append(actual.getUsername()).append("\n")
              .append("- Juego principal: ").append(actual.getNombreJuego()).append(" (").append(actual.getJuego()).append(")\n")
              .append("- Otros juegos favoritos: ").append(nullSafe(actual.getFavoriteGames())).append("\n")
              .append("- Géneros favoritos: ").append(nullSafe(actual.getFavoriteGenres())).append("\n")
              .append("- Preferencias de juego: ").append(nullSafe(actual.getPlayPreferences())).append("\n")
              .append("- Estilo: ").append(nullSafe(actual.getEstiloJuego())).append(", Rol: ").append(nullSafe(actual.getRol())).append("\n")
              .append("- Horario: ").append(nullSafe(actual.getHorarioPreferido())).append(", Región: ").append(nullSafe(actual.getRegion())).append("\n")
              .append("- Bio: ").append(nullSafe(actual.getBiografia())).append("\n")
              .append("- Valoración propia actual: ").append(String.format(Locale.US, "%.1f", actual.getStarRating())).append(" estrellas\n\n");

        prompt.append("CATÁLOGO SUGERIDO PRELIMINAR: ").append(String.join(", ", juegosBase)).append("\n\n");
        prompt.append("CANDIDATOS PÚBLICOS A EVALUAR:\n");

        int limit = Math.min(12, candidatos.size());
        for (int i = 0; i < limit; i++) {
            PerfilJugador p = candidatos.get(i).perfil;
            prompt.append(i + 1).append(". username=").append(p.getUsername())
                  .append(" | juego=").append(p.getNombreJuego())
                  .append(" | favGames=").append(nullSafe(p.getFavoriteGames()))
                  .append(" | favGenres=").append(nullSafe(p.getFavoriteGenres()))
                  .append(" | prefs=").append(nullSafe(p.getPlayPreferences()))
                  .append(" | rol=").append(nullSafe(p.getRol()))
                  .append(" | estilo=").append(nullSafe(p.getEstiloJuego()))
                  .append(" | horario=").append(nullSafe(p.getHorarioPreferido()))
                  .append(" | estrellas=").append(String.format(Locale.US, "%.1f", p.getStarRating()))
                  .append(" (").append(p.getRatingCount()).append(" votos)")
                  .append(" | bio=").append(nullSafe(p.getBiografia()))
                  .append("\n");
        }

        String sysInstruction = "Eres el motor inteligente de emparejamiento de PlayMatch en español. " +
                "Evalúa los gustos del usuario (juegos principales, otros juegos favoritos, géneros, preferencias, horario, rol y bio) frente a cada candidato. " +
                "REGLA OBLIGATORIA DE VALORACIÓN: si un candidato tiene menos de 2.0 estrellas (< 2.0), DEBES asignarle visibilityTier='REDUCIDA' y matchScore entre 8 y 34 para que se recomiende mucho menos, explicando que su baja valoración reduce su visibilidad. " +
                "Si tiene >= 4.5 estrellas y coincide en gustos/juegos, usa visibilityTier='DESTACADA'. Devuelve razones concisas y naturales en español.";

        String requestBody = "{" +
                "\"model\":\"" + JsonUtils.escape(config.getGeminiModel()) + "\"," +
                "\"system_instruction\":\"" + JsonUtils.escape(sysInstruction) + "\"," +
                "\"input\":\"" + JsonUtils.escape(prompt.toString()) + "\"," +
                "\"store\":false," +
                "\"generation_config\":{\"max_output_tokens\":1400}," +
                "\"response_format\":{" +
                  "\"type\":\"text\"," +
                  "\"mime_type\":\"application/json\"," +
                  "\"schema\":{" +
                    "\"type\":\"object\"," +
                    "\"properties\":{" +
                      "\"summary\":{\"type\":\"string\"}," +
                      "\"recommended_games\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
                      "\"recommendations\":{" +
                        "\"type\":\"array\"," +
                        "\"items\":{" +
                          "\"type\":\"object\"," +
                          "\"properties\":{" +
                            "\"username\":{\"type\":\"string\"}," +
                            "\"matchScore\":{\"type\":\"integer\"}," +
                            "\"visibilityTier\":{\"type\":\"string\"}," +
                            "\"reason\":{\"type\":\"string\"}," +
                            "\"sharedTags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}" +
                          "}," +
                          "\"required\":[\"username\",\"matchScore\",\"visibilityTier\",\"reason\"]" +
                        "}" +
                      "}" +
                    "}," +
                    "\"required\":[\"summary\",\"recommendations\"]" +
                  "}" +
                "}" +
                "}";

        HttpURLConnection conn = (HttpURLConnection) URI.create(GEMINI_INTERACTIONS_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(16000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("x-goog-api-key", config.getGeminiApiKey());
        conn.setRequestProperty("Api-Revision", API_REVISION);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String responseText = leerStream(stream);
        if (status >= 400) {
            throw new IllegalStateException("HTTP " + status + " desde Gemini API");
        }

        Map<String, Object> root = JsonUtils.asMap(JsonUtils.parseJson(responseText));
        if (root == null) return null;

        String modelJsonText = extraerTextoDeSteps(root);
        if (modelJsonText == null || modelJsonText.trim().isEmpty()) return null;

        Map<String, Object> parsedOutput = JsonUtils.asMap(JsonUtils.parseJson(modelJsonText));
        if (parsedOutput == null) return null;

        ResultadoGemini res = new ResultadoGemini();
        res.summary = JsonUtils.asString(parsedOutput.get("summary"));

        List<Object> recGamesRaw = JsonUtils.asList(parsedOutput.get("recommended_games"));
        if (recGamesRaw != null) {
            res.recommendedGames = new ArrayList<>();
            for (Object g : recGamesRaw) {
                String gs = JsonUtils.asString(g).trim();
                if (!gs.isEmpty()) res.recommendedGames.add(gs);
            }
        }

        List<Object> recsRaw = JsonUtils.asList(parsedOutput.get("recommendations"));
        if (recsRaw != null) {
            for (Object itemObj : recsRaw) {
                Map<String, Object> item = JsonUtils.asMap(itemObj);
                if (item == null) continue;
                String uname = JsonUtils.asString(item.get("username")).trim();
                if (uname.isEmpty()) continue;

                PerfilJugador placeholder = new PerfilJugador();
                placeholder.setUsername(uname);
                int score = JsonUtils.asInt(item.get("matchScore"), 50);
                String tier = JsonUtils.asString(item.get("visibilityTier"));
                String reason = JsonUtils.asString(item.get("reason"));
                List<String> tags = new ArrayList<>();
                List<Object> tagsRaw = JsonUtils.asList(item.get("sharedTags"));
                if (tagsRaw != null) {
                    for (Object t : tagsRaw) {
                        String ts = JsonUtils.asString(t).trim();
                        if (!ts.isEmpty()) tags.add(ts);
                    }
                }
                res.resultados.add(new ResultadoCandidato(placeholder, score, tier, reason, tags));
            }
        }
        return res;
    }

    private static String extraerTextoDeSteps(Map<String, Object> root) {
        List<Object> steps = JsonUtils.asList(root.get("steps"));
        if (steps == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Object stepObj : steps) {
            Map<String, Object> step = JsonUtils.asMap(stepObj);
            if (step == null || !"model_output".equals(JsonUtils.asString(step.get("type")))) continue;
            List<Object> content = JsonUtils.asList(step.get("content"));
            if (content == null) continue;
            sb.setLength(0);
            for (Object partObj : content) {
                Map<String, Object> part = JsonUtils.asMap(partObj);
                if (part != null && "text".equals(JsonUtils.asString(part.get("type")))) {
                    sb.append(JsonUtils.asString(part.get("text")));
                }
            }
        }
        return sb.toString();
    }

    private static String leerStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> sugerirJuegosPorGustos(PerfilJugador actual,
                                                        List<Juego> catalogo,
                                                        Map<String, Juego> mapaJuegos) {
        Set<String> misJuegos = extraerSetJuegos(actual);
        Set<String> misGeneros = extraerSetGeneros(actual, misJuegos, mapaJuegos);
        List<String> sugeridos = new ArrayList<>();

        for (Juego j : catalogo) {
            String code = j.getCodigo() != null ? j.getCodigo().toLowerCase(Locale.ROOT) : "";
            String cat = j.getCategoria() != null ? j.getCategoria().toLowerCase(Locale.ROOT) : "";
            if (misJuegos.contains(code) || misGeneros.stream().anyMatch(cat::contains)) {
                sugeridos.add(j.getNombre());
            }
            if (sugeridos.size() >= 4) break;
        }
        if (sugeridos.isEmpty()) {
            for (int i = 0; i < Math.min(3, catalogo.size()); i++) {
                sugeridos.add(catalogo.get(i).getNombre());
            }
        }
        return sugeridos;
    }

    private static String construirResumenLocal(PerfilJugador actual,
                                                List<ResultadoCandidato> base,
                                                List<String> juegosSugeridos) {
        String avisoPropio = actual.getStarRating() < 2.0
                ? String.format(Locale.US, " Tu valoración (%.1f★) reduce prioridad hasta superar 2.0★.", actual.getStarRating())
                : "";
        if (base.isEmpty()) {
            return "Aún no hay suficientes jugadores para comparar." + avisoPropio;
        }
        ResultadoCandidato top = base.get(0);
        return "Afinidad por juegos (" + String.join(", ", juegosSugeridos) +
                "), horario y valoración. Mejor match: @" + top.perfil.getUsername() +
                " (" + top.matchScore + "%)." + avisoPropio;
    }

    private static Set<String> extraerSetJuegos(PerfilJugador p) {
        Set<String> set = new LinkedHashSet<>(splitTokensCsv(p.getFavoriteGames()));
        if (p.getJuego() != null && !p.getJuego().trim().isEmpty()) {
            set.add(p.getJuego().trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static Set<String> extraerSetGeneros(PerfilJugador p, Set<String> juegos, Map<String, Juego> mapaJuegos) {
        Set<String> set = new LinkedHashSet<>(splitTokensCsv(p.getFavoriteGenres()));
        for (String code : juegos) {
            Juego j = mapaJuegos.get(code);
            if (j != null && j.getCategoria() != null && !j.getCategoria().trim().isEmpty()) {
                set.add(j.getCategoria().trim().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private static Set<String> splitTokensCsv(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private static Set<String> splitBioTokens(String bio) {
        Set<String> set = new LinkedHashSet<>();
        if (bio == null || bio.trim().isEmpty()) return set;
        for (String tok : bio.toLowerCase(Locale.ROOT).split("[^a-záéíóúñ0-9]+")) {
            if (tok.length() >= 4) set.add(tok);
        }
        return set;
    }

    private static String resolverNombreJuego(String code, String fallback, Map<String, Juego> mapa) {
        if (code != null) {
            Juego j = mapa.get(code.toLowerCase(Locale.ROOT));
            if (j != null && j.getNombre() != null) return j.getNombre();
        }
        return fallback != null ? fallback : "";
    }

    private static boolean rolesComplementarios(String r1, String r2) {
        if (r1 == null || r2 == null) return false;
        String a = r1.toLowerCase(Locale.ROOT);
        String b = r2.toLowerCase(Locale.ROOT);
        if (a.contains("flex") || b.contains("flex")) return true;
        return !a.equals(b) && (a.contains("duel") || a.contains("sniper") || a.contains("entry")
                || b.contains("support") || b.contains("soporte") || b.contains("control") || b.contains("tanque"));
    }

    private static boolean mismoBloqueHorario(String h1, String h2) {
        if (h1 == null || h2 == null) return false;
        String a = h1.toLowerCase(Locale.ROOT);
        String b = h2.toLowerCase(Locale.ROOT);
        return (a.contains("noche") && b.contains("noche"))
                || (a.contains("tarde") && b.contains("tarde"))
                || (a.contains("semana") && b.contains("semana"));
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
