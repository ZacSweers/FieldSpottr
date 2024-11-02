//
//  SurfaceView.swift
//  FieldSpottr
//
//  Created by Zac Sweers on 11/2/24.
//


import SwiftUI
import UIKit

// Protocol to define Surface-like behavior
protocol SurfaceViewProtocol {
    var elevation: Float { get set }
    var cornerRadius: CGFloat { get set }
    var backgroundColor: UIColor { get set }
    var shadowColor: UIColor { get set }
    var onClickHandler: (() -> Void)? { get set }
}

// UIView implementation of Surface
class SurfaceView: UIView, SurfaceViewProtocol {
    var elevation: Float = 0 {
        didSet {
            updateShadow()
        }
    }
    
    var cornerRadius: CGFloat = 0 {
        didSet {
            layer.cornerRadius = cornerRadius
            updateShadow()
        }
    }
    
    override var backgroundColor: UIColor? {
        didSet {
            super.backgroundColor = backgroundColor
        }
    }
    
    var shadowColor: UIColor = .black {
        didSet {
            updateShadow()
        }
    }
    
    var onClickHandler: (() -> Void)?
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupView()
    }
    
    private func setupView() {
        layer.masksToBounds = false
        backgroundColor = .white
        updateShadow()
        setupGestureRecognizer()
    }
    
    private func setupGestureRecognizer() {
        isUserInteractionEnabled = true
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tapGesture)
    }
    
    @objc private func handleTap() {
        // Provide visual feedback
        UIView.animate(withDuration: 0.1, animations: {
            self.alpha = 0.7
        }) { _ in
            UIView.animate(withDuration: 0.1) {
                self.alpha = 1.0
            }
        }
        onClickHandler?()
    }
    
    private func updateShadow() {
        layer.shadowColor = shadowColor.cgColor
        layer.shadowOffset = CGSize(width: 0, height: elevation)
        layer.shadowOpacity = min(elevation / 10.0, 0.5)
        layer.shadowRadius = CGFloat(elevation)
    }
}

// SwiftUI wrapper for SurfaceView
struct SurfaceViewRepresentable: UIViewRepresentable {
    var elevation: Float
    var cornerRadius: CGFloat
    var backgroundColor: UIColor
    var shadowColor: UIColor
    var onClick: (() -> Void)?
    
    func makeUIView(context: Context) -> SurfaceView {
        let view = SurfaceView()
        updateUIView(view, context: context)
        return view
    }
    
    func updateUIView(_ uiView: SurfaceView, context: Context) {
        uiView.elevation = elevation
        uiView.cornerRadius = cornerRadius
        uiView.backgroundColor = backgroundColor
        uiView.shadowColor = shadowColor
        uiView.onClickHandler = onClick
    }
}

// Callback wrapper for Compose interop
class ClickCallback {
    private let callback: () -> Void
    
    init(_ callback: @escaping () -> Void) {
        self.callback = callback
    }
    
    @objc func invoke() {
        callback()
    }
}

// Composable function for Surface
@_cdecl("createSurface")
public func createSurface(
    elevation: Float,
    cornerRadius: Float,
    backgroundColor: UInt32,
    shadowColor: UInt32,
    onClick: ComposeCallback?
) -> ComposeView {
    return ComposeView { 
        SurfaceViewRepresentable(
            elevation: elevation,
            cornerRadius: CGFloat(cornerRadius),
            backgroundColor: UIColor(rgb: backgroundColor),
            shadowColor: UIColor(rgb: shadowColor),
            onClick: onClick.map { callback in
                { callback.invoke() }
            }
        )
    }
}

// Extension to help with color conversion
extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: CGFloat((rgb >> 24) & 0xFF) / 255.0
        )
    }
}
