namespace AccessDemo2s
{
    partial class UserFingerprintInfoForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.ckb_Duress = new System.Windows.Forms.CheckBox();
            this.btn_Confirm = new System.Windows.Forms.Button();
            this.btn_Cancel = new System.Windows.Forms.Button();
            this.btn_Collection = new System.Windows.Forms.Button();
            this.lab_Result = new System.Windows.Forms.Label();
            this.SuspendLayout();
            // 
            // ckb_Duress
            // 
            this.ckb_Duress.AutoSize = true;
            this.ckb_Duress.Location = new System.Drawing.Point(52, 30);
            this.ckb_Duress.Name = "ckb_Duress";
            this.ckb_Duress.Size = new System.Drawing.Size(124, 18);
            this.ckb_Duress.TabIndex = 0;
            this.ckb_Duress.Text = "Duress()";
            this.ckb_Duress.UseVisualStyleBackColor = true;
            // 
            // btn_Confirm
            // 
            this.btn_Confirm.Location = new System.Drawing.Point(198, 182);
            this.btn_Confirm.Name = "btn_Confirm";
            this.btn_Confirm.Size = new System.Drawing.Size(120, 27);
            this.btn_Confirm.TabIndex = 41;
            this.btn_Confirm.Text = "Confirm()";
            this.btn_Confirm.UseVisualStyleBackColor = true;
            this.btn_Confirm.Click += new System.EventHandler(this.btn_Confirm_Click);
            // 
            // btn_Cancel
            // 
            this.btn_Cancel.Location = new System.Drawing.Point(52, 182);
            this.btn_Cancel.Name = "btn_Cancel";
            this.btn_Cancel.Size = new System.Drawing.Size(120, 27);
            this.btn_Cancel.TabIndex = 40;
            this.btn_Cancel.Text = "Canccel()";
            this.btn_Cancel.UseVisualStyleBackColor = true;
            this.btn_Cancel.Click += new System.EventHandler(this.btn_Cancel_Click);
            // 
            // btn_Collection
            // 
            this.btn_Collection.Location = new System.Drawing.Point(52, 73);
            this.btn_Collection.Name = "btn_Collection";
            this.btn_Collection.Size = new System.Drawing.Size(266, 27);
            this.btn_Collection.TabIndex = 42;
            this.btn_Collection.Text = "Collection fingerprint(ZW)";
            this.btn_Collection.UseVisualStyleBackColor = true;
            this.btn_Collection.Click += new System.EventHandler(this.btn_Collection_Click);
            // 
            // lab_Result
            // 
            this.lab_Result.AutoSize = true;
            this.lab_Result.Location = new System.Drawing.Point(64, 133);
            this.lab_Result.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.lab_Result.Name = "lab_Result";
            this.lab_Result.Size = new System.Drawing.Size(0, 14);
            this.lab_Result.TabIndex = 43;
            // 
            // UserFingerprintInfoForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(374, 244);
            this.Controls.Add(this.lab_Result);
            this.Controls.Add(this.btn_Collection);
            this.Controls.Add(this.btn_Confirm);
            this.Controls.Add(this.btn_Cancel);
            this.Controls.Add(this.ckb_Duress);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "UserFingerprintInfoForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "FingerprintInfo(ZW)";
            this.Load += new System.EventHandler(this.UserFingerprintInfoForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.CheckBox ckb_Duress;
        private System.Windows.Forms.Button btn_Confirm;
        private System.Windows.Forms.Button btn_Cancel;
        private System.Windows.Forms.Button btn_Collection;
        private System.Windows.Forms.Label lab_Result;
    }
}