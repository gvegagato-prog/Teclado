import AppKit

/// La pera mordida de Pera, dibujada (misma silueta que el icono de Android, en una cuadrícula de 24).
/// Es una imagen «plantilla»: macOS la pinta en blanco o negro según la barra de menús.
enum PearIcon {
    /// [active]: hotspot del móvil en uso (pera llena); si no, más tenue. [busy]: aún más tenue.
    static func image(active: Bool, busy: Bool) -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let img = NSImage(size: size, flipped: true) { _ in
            let s = size.width / 24
            func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: x * s, y: y * s) }
            let alpha: CGFloat = busy ? 0.35 : (active ? 1 : 0.6)
            NSColor.black.withAlphaComponent(alpha).setFill()

            // Cuerpo con el mordisco a la derecha (curva cóncava entre (18,15.6) y (15.6,10.9)).
            let body = NSBezierPath()
            body.move(to: p(12, 6.2))
            body.curve(to: p(9.4, 8.6), controlPoint1: p(10.7, 6.2), controlPoint2: p(9.6, 7.2))
            body.curve(to: p(7.5, 11.9), controlPoint1: p(9.2, 9.9), controlPoint2: p(8.5, 10.9))
            body.curve(to: p(6, 15.6), controlPoint1: p(6.6, 12.9), controlPoint2: p(6, 14.1))
            body.curve(to: p(12, 21.5), controlPoint1: p(6, 18.9), controlPoint2: p(8.7, 21.5))
            body.curve(to: p(18, 15.6), controlPoint1: p(15.3, 21.5), controlPoint2: p(18, 18.9))
            body.curve(to: p(15.6, 10.9), controlPoint1: p(15.62, 15.56), controlPoint2: p(14.27, 12.87))
            body.curve(to: p(14.6, 8.6), controlPoint1: p(15.1, 10.3), controlPoint2: p(14.7, 9.5))
            body.curve(to: p(12, 6.2), controlPoint1: p(14.4, 7.2), controlPoint2: p(13.3, 6.2))
            body.close()
            body.fill()

            // Rabito y hoja.
            NSBezierPath(rect: NSRect(x: 11.4 * s, y: 2.5 * s, width: 1.2 * s, height: 3.4 * s)).fill()
            let leaf = NSBezierPath()
            leaf.move(to: p(12.6, 4.6))
            leaf.curve(to: p(16.2, 2.5), controlPoint1: p(13.2, 3.2), controlPoint2: p(14.6, 2.4))
            leaf.curve(to: p(12.6, 4.6), controlPoint1: p(15.8, 4.0), controlPoint2: p(14.4, 5.0))
            leaf.close()
            leaf.fill()
            return true
        }
        img.isTemplate = true
        return img
    }
}
