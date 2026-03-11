/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect } from 'react';
import { QrCode } from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

export default function App() {
  const [isScanning, setIsScanning] = useState(true);

  // Simulate a timeout for the "Scanning" state to show the "Failed" state
  useEffect(() => {
    if (isScanning) {
      const timer = setTimeout(() => {
        setIsScanning(false);
      }, 5000);
      return () => clearTimeout(timer);
    }
  }, [isScanning]);

  const handleToggle = () => {
    setIsScanning(!isScanning);
  };

  return (
    <div className="min-h-screen bg-[#0A0A0A] flex items-center justify-center p-4 font-sans text-white">
      {/* Main Panel - Native Meta Quest System Style */}
      <motion.div 
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: [0.23, 1, 0.32, 1] }}
        className="w-full max-w-[400px] bg-[#1C1C1C] rounded-[28px] shadow-[0_24px_48px_rgba(0,0,0,0.4)] overflow-hidden flex flex-col"
        id="qr-scanner-panel"
      >
        {/* Header Section */}
        <div className="px-6 pt-6 pb-4 flex items-center gap-3" id="header-section">
          <div className="bg-[#2D2D2D] p-2 rounded-xl flex items-center justify-center" id="qr-icon-container">
            <QrCode className="w-5 h-5 text-white/90" />
          </div>
          <h1 className="text-[20px] font-medium tracking-tight" id="header-title">
            QR Scanner
          </h1>
        </div>

        {/* Main Content Area */}
        <div className="px-6 flex-1 flex flex-col items-center" id="main-content">
          {/* Scanning Zone - Rounded Rectangular */}
          <div 
            className="relative w-full aspect-[4/3] bg-[#121212] rounded-[20px] flex flex-col items-center justify-center overflow-hidden"
            id="scanning-zone"
          >
            {/* Scanning Indicator Container */}
            <div className="relative mb-6 flex items-center justify-center h-20 w-20" id="indicator-container">
              <AnimatePresence>
                {isScanning && (
                  <>
                    {/* Pulsing Ring */}
                    <motion.div 
                      initial={{ opacity: 0, scale: 0.8 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.8 }}
                      className="absolute inset-0 flex items-center justify-center"
                    >
                      <motion.div 
                        animate={{ 
                          scale: [1, 1.4, 1],
                          opacity: [0.3, 0.1, 0.3]
                        }}
                        transition={{ duration: 3, repeat: Infinity, ease: "easeInOut" }}
                        className="w-16 h-16 border-2 border-[#4A90E2] rounded-full blur-[2px]"
                      />
                    </motion.div>

                    {/* Rotating Brackets */}
                    <motion.div 
                      initial={{ opacity: 0, rotate: -45 }}
                      animate={{ opacity: 1, rotate: 315 }}
                      exit={{ opacity: 0 }}
                      transition={{ 
                        opacity: { duration: 0.3 },
                        rotate: { duration: 10, repeat: Infinity, ease: "linear" }
                      }}
                      className="absolute w-12 h-12"
                    >
                      <div className="absolute top-0 left-0 w-3 h-3 border-t-2 border-l-2 border-[#4A90E2]/60 rounded-tl-sm" />
                      <div className="absolute top-0 right-0 w-3 h-3 border-t-2 border-r-2 border-[#4A90E2]/60 rounded-tr-sm" />
                      <div className="absolute bottom-0 left-0 w-3 h-3 border-b-2 border-l-2 border-[#4A90E2]/60 rounded-bl-sm" />
                      <div className="absolute bottom-0 right-0 w-3 h-3 border-b-2 border-r-2 border-[#4A90E2]/60 rounded-tr-sm" />
                    </motion.div>

                    {/* Radar Sweep */}
                    <motion.div 
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 0.15 }}
                      exit={{ opacity: 0 }}
                      className="absolute inset-0 bg-[conic-gradient(from_0deg,transparent_0deg,#4A90E2_360deg)] rounded-full"
                      style={{ maskImage: 'radial-gradient(circle, transparent 40%, black 100%)', WebkitMaskImage: 'radial-gradient(circle, transparent 40%, black 100%)' }}
                    >
                      <motion.div 
                        animate={{ rotate: 360 }}
                        transition={{ duration: 4, repeat: Infinity, ease: "linear" }}
                        className="w-full h-full"
                      />
                    </motion.div>
                  </>
                )}
              </AnimatePresence>

              {/* Static Center Dot (Native feel) */}
              <div className="relative w-1.5 h-1.5 bg-white/40 rounded-full" />
            </div>

          </div>
        </div>

        {/* Footer Action */}
        <div className="p-6" id="footer-section">
          <button 
            onClick={handleToggle}
            disabled={isScanning}
            className={`
              w-full py-3.5 transition-all rounded-full font-medium text-[15px] border-t border-white/5 relative overflow-hidden
              ${isScanning 
                ? 'bg-[#2D2D2D] text-white/30 cursor-default' 
                : 'bg-[#3B4A5A] hover:bg-[#455668] active:bg-[#323E4C] text-white shadow-[0_2px_4px_rgba(0,0,0,0.2)]'
              }
            `}
            id="action-button"
          >
            {isScanning ? (
              <span className="flex items-center justify-center gap-2">
                Scanning…
                <motion.div 
                  animate={{ opacity: [0.3, 1, 0.3] }}
                  transition={{ duration: 1.5, repeat: Infinity }}
                  className="absolute inset-0 bg-gradient-to-r from-transparent via-white/5 to-transparent -skew-x-12 translate-x-[-100%]"
                  style={{ animation: 'shimmer 2s infinite' }}
                />
              </span>
            ) : (
              "Scan again"
            )}
          </button>
        </div>
      </motion.div>

      <style dangerouslySetInnerHTML={{ __html: `
        @keyframes shimmer {
          0% { transform: translateX(-100%) skewX(-12deg); }
          100% { transform: translateX(200%) skewX(-12deg); }
        }
      `}} />
    </div>
  );
}
