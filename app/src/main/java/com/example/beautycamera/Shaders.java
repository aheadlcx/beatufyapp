package com.example.beautycamera;

/** All GLSL sources, kept apart from pipeline logic. */
public final class Shaders {
    private Shaders() {
    }

    public static final String VS =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "uniform vec2 uCrop;\n" +
        "uniform float uMirror;\n" +
        "uniform float uRot;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vec2 uv = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
        "    if (uRot != 0.0) {\n" +
        "        vec2 c = uv - 0.5;\n" +
        "        float a = radians(uRot);\n" +
        "        uv = vec2(c.x * cos(a) - c.y * sin(a), c.x * sin(a) + c.y * cos(a)) + 0.5;\n" +
        "    }\n" +
        "    if (uMirror > 0.5) uv.x = 1.0 - uv.x;\n" +
        "    vUV = (uv - 0.5) * uCrop + 0.5;\n" +
        "}\n";

    public static final String FS_OES =
        "precision mediump float;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform float uAspect;\n" +
        "uniform int uWarpCount;\n" +
        "uniform vec3 uWarpCR[" + 12 + "];\n" +
        "uniform vec2 uWarpD[" + 12 + "];\n" +
        "uniform float uWarpType[" + 12 + "];\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec2 p = (vUV - 0.5) * vec2(uAspect, 1.0);\n" +
        "    for (int i = 0; i < " + 12 + "; i++) {\n" +
        "        if (i >= uWarpCount) break;\n" +
        "        vec2 c = (uWarpCR[i].xy - 0.5) * vec2(uAspect, 1.0);\n" +
        "        float r = uWarpCR[i].z;\n" +
        "        vec2 d = p - c;\n" +
        "        float dist = length(d);\n" +
        "        if (dist < r) {\n" +
        "            float t = 1.0 - dist / r;\n" +
        "            float fall = t * t;\n" +
        "            if (uWarpType[i] < 0.5) {\n" +
        "                p = c + d * (1.0 - uWarpD[i].x * fall);\n" +
        "            } else {\n" +
        "                p = p - uWarpD[i] * fall;\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "    vec2 uv = p / vec2(uAspect, 1.0) + 0.5;\n" +
        "    gl_FragColor = texture2D(sTexture, clamp(uv, 0.001, 0.999));\n" +
        "}\n";

    public static final String FS_COPY =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "varying vec2 vUV;\n" +
        "void main() { gl_FragColor = texture2D(sTexture, vUV); }\n";

    public static final String FS_BLUR =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "uniform vec2 uTexel;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec3 sum = texture2D(sTexture, vUV).rgb * 0.227027;\n" +
        "    sum += texture2D(sTexture, vUV + uTexel * 1.3846).rgb * 0.316216;\n" +
        "    sum += texture2D(sTexture, vUV - uTexel * 1.3846).rgb * 0.316216;\n" +
        "    sum += texture2D(sTexture, vUV + uTexel * 3.2308).rgb * 0.070270;\n" +
        "    sum += texture2D(sTexture, vUV - uTexel * 3.2308).rgb * 0.070270;\n" +
        "    gl_FragColor = vec4(sum, 1.0);\n" +
        "}\n";

    public static final String FS_LUMA =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec3 c = texture2D(sTexture, vUV).rgb;\n" +
        "    float y = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "    gl_FragColor = vec4(y, y, y, 1.0);\n" +
        "}\n";

    public static final String FS_MUL =
        "precision mediump float;\n" +
        "uniform sampler2D sTex1;\n" +
        "uniform sampler2D sTex2;\n" +
        "varying vec2 vUV;\n" +
        "void main() { gl_FragColor = texture2D(sTex1, vUV) * texture2D(sTex2, vUV); }\n";

    public static final String FS_COMBINE_A =
        "precision mediump float;\n" +
        "uniform sampler2D sI;\n" +
        "uniform sampler2D sII;\n" +
        "uniform sampler2D sIP;\n" +
        "uniform sampler2D sP;\n" +
        "uniform float uEps;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float mI = texture2D(sI, vUV).r;\n" +
        "    float mII = texture2D(sII, vUV).r;\n" +
        "    float mIP = texture2D(sIP, vUV).r;\n" +
        "    float mP = texture2D(sP, vUV).r;\n" +
        "    float varI = mII - mI * mI;\n" +
        "    float covIP = mIP - mI * mP;\n" +
        "    float a = covIP / (varI + uEps);\n" +
        "    gl_FragColor = vec4(a, 0.0, 0.0, 1.0);\n" +
        "}\n";

    public static final String FS_COMBINE_B =
        "precision mediump float;\n" +
        "uniform sampler2D sA;\n" +
        "uniform sampler2D sI;\n" +
        "uniform sampler2D sP;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float a = texture2D(sA, vUV).r;\n" +
        "    float mI = texture2D(sI, vUV).r;\n" +
        "    float mP = texture2D(sP, vUV).r;\n" +
        "    gl_FragColor = vec4(mP - a * mI, 0.0, 0.0, 1.0);\n" +
        "}\n";

    public static final String FS_APPLY_Q =
        "precision mediump float;\n" +
        "uniform sampler2D sA;\n" +
        "uniform sampler2D sI;\n" +
        "uniform sampler2D sB;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float a = texture2D(sA, vUV).r;\n" +
        "    float i = texture2D(sI, vUV).r;\n" +
        "    float b = texture2D(sB, vUV).r;\n" +
        "    gl_FragColor = vec4(a * i + b, a * i + b, a * i + b, 1.0);\n" +
        "}\n";

    public static final String FS_FINAL =
        "precision mediump float;\n" +
        "uniform sampler2D sBase;\n" +
        "uniform sampler2D sQ;\n" +
        "uniform sampler2D sBg;\n" +
        "uniform sampler2D sMask;\n" +
        "uniform float uHasMask;\n" +
        "uniform float uMaskMirror;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform float uSmooth;\n" +
        "uniform float uWhiten;\n" +
        "uniform float uRuddy;\n" +
        "uniform float uSharpen;\n" +
        "uniform float uSaturate;\n" +
        "uniform float uSkinTone;\n" +
        "uniform float uEyeCircle;\n" +
        "uniform float uContour;\n" +
        "uniform vec2 uEyeL;\n" +
        "uniform vec2 uEyeR;\n" +
        "uniform float uEyeDist;\n" +
        "uniform vec2 uNoseBase;\n" +
        "uniform vec2 uCheekL;\n" +
        "uniform vec2 uCheekR;\n" +
        "uniform float uDispAspect;\n" +
        "uniform float uBgMix;\n" +
        "uniform int uFilter;\n" +
        "uniform float uFilterStrength;\n" +
        "varying vec2 vUV;\n" +

        "float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +

        "float skinMask(vec3 c) {\n" +
        "    float y = lum(c);\n" +
        "    float cb = 0.5 - 0.169 * c.r - 0.331 * c.g + 0.5 * c.b;\n" +
        "    float cr = 0.5 + 0.5 * c.r - 0.419 * c.g - 0.081 * c.b;\n" +
        "    float m1 = smoothstep(0.29, 0.34, cb) * (1.0 - smoothstep(0.48, 0.53, cb));\n" +
        "    float m2 = smoothstep(0.51, 0.55, cr) * (1.0 - smoothstep(0.71, 0.75, cr));\n" +
        "    return m1 * m2 * smoothstep(0.05, 0.2, y);\n" +
        "}\n" +

        "vec3 applyFilter(vec3 c) {\n" +
        "    vec3 f = c;\n" +
        "    if (uFilter == 1) {\n" +
        "        f = c * 1.10 + 0.03;\n" +
        "        f = mix(vec3(lum(f)), f, 0.92);\n" +
        "    } else if (uFilter == 2) {\n" +
        "        f = vec3(c.r * 1.09, c.g * 1.03, c.b * 0.92) * 1.05;\n" +
        "    } else if (uFilter == 3) {\n" +
        "        f = vec3(c.r * 0.94, c.g * 1.00, c.b * 1.09);\n" +
        "    } else if (uFilter == 4) {\n" +
        "        f = mix(c, c * c * (3.0 - 2.0 * c), 0.4);\n" +
        "        float d = length(vUV - 0.5);\n" +
        "        f *= 1.0 - 0.30 * d * d;\n" +
        "        f.b += 0.02;\n" +
        "    } else if (uFilter == 5) {\n" +
        "        f = vec3(c.r + 0.05, c.g + 0.02, c.b + 0.04);\n" +
        "        f = mix(vec3(lum(f)), f, 1.08);\n" +
        "    } else if (uFilter == 6) {\n" +
        "        f = vec3(lum(c));\n" +
        "    } else if (uFilter == 7) {\n" +
        "        f = vec3(lum(c)) * vec3(1.14, 1.0, 0.82);\n" +
        "        f = mix(c, f, 0.85);\n" +
        "    } else if (uFilter == 8) {\n" +
        "        f = vec3(c.r + 0.06, c.g * 0.97 + 0.01, c.b * 0.96 + 0.04);\n" +
        "        f = mix(vec3(lum(f)), f, 0.9);\n" +
        "        f = pow(clamp(f, 0.0, 1.0), vec3(0.92));\n" +
        "    } else if (uFilter == 9) {\n" +
        "        f = vec3(c.r * 1.07 + 0.03, c.g * 0.99, c.b * 0.95 + 0.02);\n" +
        "        f = mix(vec3(lum(f)), f, 1.05);\n" +
        "    } else if (uFilter == 10) {\n" +
        "        f = vec3(c.r * 0.92, c.g * 1.0, c.b * 1.12) * 1.03;\n" +
        "    } else if (uFilter == 11) {\n" +
        "        f = vec3(c.r * 0.92 + 0.01, c.g * 1.06 + 0.02, c.b * 0.94);\n" +
        "        f = mix(vec3(lum(f)), f, 0.95);\n" +
        "    }\n" +
        "    return mix(c, clamp(f, 0.0, 1.0), uFilterStrength);\n" +
        "}\n" +

        "float eyeMask(vec2 eye) {\n" +
        "    vec2 d = vUV - (eye - vec2(0.0, uEyeDist * 0.16));\n" +
        "    d.x *= uDispAspect;\n" +
        "    float r = uEyeDist * 0.34;\n" +
        "    float len = length(d / vec2(1.0, 1.35));\n" +
        "    return smoothstep(r, r * 0.25, len);\n" +
        "}\n" +

        "void main() {\n" +
        "    vec3 base = texture2D(sBase, vUV).rgb;\n" +
        "    vec3 q = texture2D(sQ, vUV).rgb;\n" +
        "    float skin = skinMask(base);\n" +

        "    float detail = length(base - q);\n" +
        "    float edge = smoothstep(0.02, 0.16, detail);\n" +
        "    float w = clamp(uSmooth, 0.0, 1.0) * (1.0 - edge) * mix(0.35, 1.0, skin);\n" +
        "    vec3 color = mix(base, q, w);\n" +

        "    if (uEyeCircle > 0.001 && uEyeDist > 0.0) {\n" +
        "        float m = max(eyeMask(uEyeL), eyeMask(uEyeR));\n" +
        "        vec3 fixedC = q * 1.05 + 0.025;\n" +
        "        color = mix(color, fixedC, m * uEyeCircle * 0.85);\n" +
        "    }\n" +

        "    if (uContour > 0.001) {\n" +
        "        float eyeMidY = (uEyeL.y + uEyeR.y) * 0.5;\n" +
        "        float lineX = (uEyeL.x + uEyeR.x) * 0.5;\n" +
        "        float band = smoothstep(eyeMidY + uEyeDist * 0.35, eyeMidY + uEyeDist * 0.75, vUV.y)\n" +
        "                   * (1.0 - smoothstep(uNoseBase.y, uNoseBase.y - uEyeDist * 0.2, vUV.y));\n" +
        "        float dxn = abs(vUV.x - lineX) * uDispAspect;\n" +
        "        float shadow = smoothstep(uEyeDist * 0.22, 0.0, dxn) * band;\n" +
        "        color -= shadow * uContour * 0.085 * skin;\n" +
        "        vec2 dl = vUV - (uCheekL + vec2(0.0, uEyeDist * 0.22));\n" +
        "        vec2 dr = vUV - (uCheekR + vec2(0.0, uEyeDist * 0.22));\n" +
        "        dl.x *= uDispAspect;\n" +
        "        dr.x *= uDispAspect;\n" +
        "        float hl = smoothstep(uEyeDist * 0.55, uEyeDist * 0.12, length(dl))\n" +
        "                 + smoothstep(uEyeDist * 0.55, uEyeDist * 0.12, length(dr));\n" +
        "        color += min(hl, 1.0) * uContour * 0.045 * skin;\n" +
        "    }\n" +

        "    if (uSkinTone > 0.001) {\n" +
        "        float y = lum(color);\n" +
        "        float cb = 0.5 - 0.169 * color.r - 0.331 * color.g + 0.5 * color.b;\n" +
        "        float cr = 0.5 + 0.5 * color.r - 0.419 * color.g - 0.081 * color.b;\n" +
        "        cb = mix(cb, 0.53, uSkinTone * skin * 0.45);\n" +
        "        cr = mix(cr, 0.44, uSkinTone * skin * 0.55);\n" +
        "        color = vec3(y + 1.402 * (cr - 0.5),\n" +
        "                     y - 0.344 * (cb - 0.5) - 0.714 * (cr - 0.5),\n" +
        "                     y + 1.772 * (cb - 0.5));\n" +
        "    }\n" +

        "    vec3 nb2 = texture2D(sBase, vUV + vec2(uTexel.x, 0.0)).rgb\n" +
        "             + texture2D(sBase, vUV - vec2(uTexel.x, 0.0)).rgb\n" +
        "             + texture2D(sBase, vUV + vec2(0.0, uTexel.y)).rgb\n" +
        "             + texture2D(sBase, vUV - vec2(0.0, uTexel.y)).rgb;\n" +
        "    color += (base * 4.0 - nb2) * uSharpen * 0.35;\n" +

        "    float l2 = lum(color);\n" +
        "    color = mix(vec3(l2), color, 1.0 + uSaturate);\n" +

        "    if (uBgMix > 0.001) {\n" +
        "        vec3 bg = texture2D(sBg, vUV).rgb;\n" +
        "        vec2 mUV = vUV;\n" +
        "        if (uMaskMirror > 0.5) mUV.x = 1.0 - mUV.x;\n" +
        "        float fg = uHasMask > 0.5 ? texture2D(sMask, mUV).r : 1.0;\n" +
        "        color = mix(color, bg, uBgMix * (1.0 - fg));\n" +
        "    }\n" +

        "    color = applyFilter(color);\n" +
        "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
        "}\n";
}
