# Register constants imported by anisette's ADI hooks (anisette/_hooks.py).
# Only the subset anisette actually references is defined; the values match
# upstream unicorn so that any accidental use is at least not silently wrong.

UC_ARM64_REG_FP = 1
UC_ARM64_REG_LR = 2
UC_ARM64_REG_SP = 3
UC_ARM64_REG_W13 = 163
UC_ARM64_REG_W14 = 164
UC_ARM64_REG_W15 = 165
UC_ARM64_REG_X0 = 190
UC_ARM64_REG_X1 = 191
UC_ARM64_REG_X2 = 192
