// lid.176.ftz, compiled into libwmfasttext.so; build.sh passes its path in
// WM_LID_MODEL after checking its hash.
    .section .rodata
    .balign 16
    .globl wm_lid176
    .hidden wm_lid176
wm_lid176:
    .incbin WM_LID_MODEL
    .globl wm_lid176_end
    .hidden wm_lid176_end
wm_lid176_end:
    .byte 0
    .section .note.GNU-stack,"",%progbits
